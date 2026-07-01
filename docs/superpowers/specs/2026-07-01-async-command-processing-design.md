# Async command processing for `lib-cmd-queue-redis` — Approach A

**Date:** 2026-07-01
**Module:** `lib-cmd-queue-redis` (current v0.4.0)
**Status:** approved for implementation (revised after adversarial spec review)

## 1. Problem

Today the command queue processes messages **serially on a single poll thread**.
`AbstractMessageStream.processMessages()` loops over each registered stream and calls
`RedisMessageStream.consume()`, which runs the consumer **synchronously** and blocks the
poll thread. `CommandServiceImpl` submits `handler.execute()` to the shared blocking
executor but then **waits up to `executeTimeout` (default 1s)** for it
(`executeWithTimeout`). Net effect: throughput ≈ one command at a time per replica, and
each command occupies the poll thread for up to the timeout.

Long-running work is handled indirectly: on timeout the overrun `execute()` future's
result is **discarded**, the command is marked `RUNNING`, and on redelivery the handler's
`checkStatus()` is polled until terminal. This assumes the real work lives **outside** our
threads (an external job the handler polls).

**Goal:** run command handlers to completion on a **bounded worker pool** so multiple
commands execute concurrently and the poll thread never blocks on a handler — while
guaranteeing that a given command's `execute()` is **not run concurrently on more than one
replica** ("exactly-once-ish": single concurrent runner, at-least-once on crash) — and
**without breaking** the existing `execute()`/`checkStatus()` external-job pattern.

## 2. Goals / Non-goals

**Goals**
- Poll thread dispatches each message to a bounded pool and returns immediately (non-blocking).
- Handlers may block for arbitrarily long on a worker thread (in-process pattern); `checkStatus()` stays supported (external-job pattern).
- No concurrent double-execution of the same command across replicas; automatic recovery if a replica dies.
- Backpressure: bounded pool refuses excess work; refused messages stay in the queue.
- **No change to the shared `lib-data-stream-redis` interface.**
- Backward compatible: existing `CommandHandler`/`CommandConfig`/`CommandService`/`CommandRegistration` consumers keep working, and existing tests keep passing.

**Non-goals**
- No change to `MessageStream` / `AbstractMessageStream` / `RedisMessageStream` / `LocalMessageStream`.
- No new ack/renew primitive on the stream (that is Approach B — out of scope).
- No ordering guarantees beyond what Redis Streams already provide.
- No interruption of a running handler on `cancel()` (cancel is advisory — see §5).

## 3. The two deliberate simplifications

| # | Shortcut | Taken here? |
|---|----------|-------------|
| 1 | Poll thread **waits ~5s** for the handler, then offloads (the "Future + 5s" two-tier) | **NOT taken.** Dispatch is fully non-blocking: every command is offloaded to the pool immediately, so there is nothing to "wait 5s then move." The 5s threshold from the original sketch is superseded. |
| 2 | Worker **does not ack**; the finished entry is acked later, on the next reclaim, when the store shows a terminal state | **TAKEN.** This is the only place `true` is returned to the stream, so the only place `XACK`+`XDEL` happens. Keeps the stream interface untouched. |

### Simplification #2 in detail (the ack path)

`RedisMessageStream.consume()` acks (`XACK`+`XDEL`) **only** when the consumer returns
`true`. During execution the poll thread returns `false`, so the entry stays **unacked** in
the pending-entries list (PEL). The worker, on completion, writes the terminal state to the
**store** — it never touches the stream. The entry is removed only when a later poll cycle
**reclaims** it (`XAUTOCLAIM`, once idle > `claimTimeout`) and the consumer, seeing a
terminal state, returns `true`.

Consequence — a finished entry **lingers unacked up to one `claimTimeout`** before removal.
Harmless: the **result is visible in the store immediately** (`getState`/`getResult` reflect
completion the instant the worker writes terminal); only the stream entry lingers, and the
terminal-state check prevents re-execution during the linger.

Upgrade path if the linger ever matters: expose the claimed entry id + add a direct
`ack(streamId, id)` primitive — the interface change Approach B is built on. Not done here.

## 4. Design

Collaborators inside `CommandServiceImpl` (plus one new factory). No stream changes.

1. **Bounded worker pool** — `ThreadPoolExecutor(poolSize, poolSize, 0, MILLISECONDS,
   new ArrayBlockingQueue<>(queueCapacity))` with a named daemon thread factory, owned as a
   field. Runs the handler to completion. Replaces the injected
   `@Named(TaskExecutors.BLOCKING) ExecutorService` and the `executeWithTimeout` logic. Lifecycle in §4.6.
2. **In-flight set** — `Set<String> inFlight = ConcurrentHashMap.newKeySet()`. `inFlight.add(id)`
   is the **atomic dedup gate**: the poll thread adds the id *before* submitting to the pool;
   the worker removes it in a `finally`. Prevents the **same replica** from double-dispatching
   a command whose own pending entry is reclaimed mid-run. (Registering before submit closes
   the "worker finishes and removes before the poll thread records it" race.)
3. **Single-runner lock** — `io.seqera.lock.LockManager` (`lib-lock`), injected
   `@Named(COMMAND_LOCK)`. `tryAcquire(key)` is **non-blocking** (`null` if held elsewhere). The
   Redis impl uses `SET NX PX` + a **watchdog** that renews the TTL every `ttl/3` while the
   holder is alive; the key expires on crash. Cross-replica single-runner + crash recovery, no
   hand-rolled heartbeat. In non-Redis mode `LocalLockManager` (in-JVM) is auto-selected.
4. **checkStatus discrimination** — at `registerHandler`, compute once (via reflection) whether
   the handler **overrides** `checkStatus`, cache in `Map<String,Boolean>`:
   - override present ⇒ **external-job** handler (poll via `checkStatus`).
   - not present ⇒ **in-process** handler (`execute` blocks to completion).
   `CommandRegistration` (a published record) is **not** modified.
5. **State store** — unchanged. The worker writes `RUNNING` then the terminal state.

Task kinds dispatched to the pool: `EXECUTE` (call `handler.execute`) and `CHECK_STATUS`
(call `handler.checkStatus`).

### 4.1 `processCommand(msg)` — poll thread, fast, never runs a handler

```
state = store.findById(id).orElse(null)
if state == null:                       return true    // stale, ack & drop
if state.status().isTerminal():         return true    // ACK POINT (simplification #2)
registration = getHandler(state.type())
if registration == null:                store.save(state.failed("No handler…")); return true
if !inFlight.add(id):                   return false   // already running here; wait for reclaim
lock = lockManager.tryAcquire(lockKey(id))
if lock == null:                        inFlight.remove(id); return false   // another replica owns it
task = decideTask(state, registration)  // see §4.2
try:
    pool.execute(() -> runCommand(id, registration, lock, task))
    return false                                         // leave unacked; ack later when terminal
catch RejectedExecutionException:                        // pool saturated → backpressure
    inFlight.remove(id); lock.release(); return false
```

`lockKey(id)` = `"cmd-queue/lock/" + id`. Ordering guarantee (must hold): the absent/terminal
checks precede the `inFlight` check, so a stale in-flight entry can never block an ack.

`decideTask(state, registration)`:
- `state.status() == RUNNING && hasCustomCheckStatus(type)` → `CHECK_STATUS`
- otherwise (`SUBMITTED`, or `RUNNING` for an in-process handler = crash re-run) → `EXECUTE`

### 4.2 `runCommand(id, registration, lock, task)` — worker thread, to completion

```
try:
    current = store.findById(id).orElse(null)
    if current == null || current.status().isTerminal():   // cancelled/gone before we started
        return
    command = toCommand(current, registration)
    if task == EXECUTE:
        if current.status() != RUNNING:
            store.save(current.started())                  // SUBMITTED → RUNNING
        result = registration.handler().execute(command)
    else: // CHECK_STATUS
        result = registration.handler().checkStatus(command, current)

    if result.status() == RUNNING:                         // external job still in progress
        if current.status() != RUNNING:
            store.save(current.started())
        return                                             // leave unacked; redelivery re-polls
    // terminal result → apply, but do not clobber a concurrent cancel (best-effort, see §5)
    latest = store.findById(id).orElse(current)
    if !latest.status().isTerminal():
        store.save(latest.applyResult(result))
catch Exception e:
    latest = store.findById(id).orElse(current)
    if latest != null && !latest.status().isTerminal():
        store.save(latest.failed(e.getMessage()))
finally:
    inFlight.remove(id)
    lock.release()                                         // watchdog stops; key deleted
```

`finally` order: terminal state is written **before** `inFlight.remove`/`lock.release`, so any
reclaim racing completion either sees us in-flight (→ `false`) or sees terminal (→ ack).

### 4.3 Correctness

- **Single concurrent runner (cross-replica).** A replica runs a handler only while holding the
  lock. A fresh message (`XREADGROUP`) goes to exactly one consumer; on reclaim another replica
  must `tryAcquire` first and gets `null` while the holder is alive (watchdog renewing) → it does
  not run. ⇒ at most one replica executes at a time.
- **Crash recovery (at-least-once).** Holder dies → watchdog stops → lock TTL expires; the
  message was never acked, so a later `XAUTOCLAIM` reclaims it and `tryAcquire` now succeeds.
  In-process handler → `EXECUTE` re-runs the work; external-job handler → `CHECK_STATUS` polls the
  external job that survived the crash. Recovery latency ≈ `max(claimTimeout, lockTTL)` after death.
- **Same-replica redelivery.** `inFlight.add` short-circuits before the lock, so a replica never
  double-runs its own command when its own pending entry is reclaimed mid-run.
- **Backpressure.** Bounded pool queue; on `RejectedExecutionException` release the lock, remove
  in-flight, leave the message queued for a later reclaim.
- **Double-exec caveat (documented, inherent to distributed locks).** The window is bounded by
  the lock TTL **only while the watchdog is healthy**. The watchdog survives transient renewal
  errors (they are caught and retried at the next `ttl/3` tick) and only stops on a *confirmed*
  ownership loss. But a sustained inability to renew — a JVM pause or a Redis partition longer than
  the TTL, without the process dying — lets the key expire while the handler still runs, so a
  second replica may start a concurrent run. This is standard lock-based "exactly-once-ish", not
  true exactly-once. Set `commandLockDuration` comfortably larger than expected pauses. The
  watchdog **must stay enabled** (`LockConfig.watchdogEnabled` default `true`) — disabling it
  breaks the hold-while-alive guarantee.

### 4.4 Configuration (additive, backward compatible)

New **default** methods on `CommandConfig` (existing implementors keep compiling):

```java
default int commandPoolSize()          { return 10; }                  // worker threads
default int commandPoolQueueSize()     { return 100; }                 // bounded queue → backpressure
default Duration commandLockDuration() { return Duration.ofMinutes(1); } // lock TTL (watchdog renews ttl/3)
```

`executeTimeout()` is **kept** and marked `@Deprecated` (removing it from a published interface
would break `TestCommandConfig` and any external implementor). Its Javadoc — and the "1 second"
note in `CommandHandler.execute()` — are updated to state the value is no longer consulted and
the handler now runs to completion on the worker pool. No `config.executeTimeout()` reference may
remain in `CommandServiceImpl`.

### 4.5 Wiring (`CommandLockFactory`, new file)

Self-provisions one `@Named(COMMAND_LOCK) LockManager` so consumers need **no**
`seqera.lock.*` config and the module works out of the box on upgrade:

```java
@Factory
public class CommandLockFactory {
    // reserved qualifier, unlikely to collide with a host seqera.lock.<name> key
    static final String COMMAND_LOCK = "cmd-queue-internal-lock";

    @Singleton @Named(COMMAND_LOCK) @Requires(bean = RedisActivator.class)
    LockManager redis(JedisPool pool, TaskScheduler scheduler, CommandConfig config) {
        var cfg = new LockConfig(COMMAND_LOCK);
        cfg.setAutoExpireDuration(config.commandLockDuration());   // watchdogEnabled stays true (default)
        return new RedisLockManager(cfg, pool, scheduler);
    }
    @Singleton @Named(COMMAND_LOCK) @Requires(missingBeans = RedisActivator.class)
    LockManager local(CommandConfig config) {
        var cfg = new LockConfig(COMMAND_LOCK);
        cfg.setAutoExpireDuration(config.commandLockDuration());
        return new LocalLockManager(cfg);
    }
}
```

`CommandServiceImpl` injects `@Inject @Named(CommandLockFactory.COMMAND_LOCK) LockManager lockManager`.

**Bean-collision note:** `lib-lock`/`lib-lock-redis` register `@EachBean(LockConfig)` `LockManager`
beans, one per configured `seqera.lock.<name>`, qualified by name. A host that configures
`seqera.lock.cmd-queue-internal-lock` would collide with our qualifier — hence the deliberately
reserved name. This is documented as reserved. A Micronaut context test (below) guards resolution.

**Runtime precondition (redis mode):** the host must provide a `RedisActivator` bean and a
`JedisPool` bean (e.g. via `lib-jedis-pool`). This already holds wherever this module runs against
Redis, because `RedisMessageStream` and the state store it already depends on require the same
`JedisPool`. Test fixtures (`lib-fixtures-redis`) provide both under the `redis` env.

New `build.gradle` dependencies:
```
implementation project(':lib-lock')
implementation project(':lib-lock-redis')
implementation project(':lib-activator')        // RedisActivator on the compile classpath
implementation 'redis.clients:jedis:5.1.4'       // JedisPool referenced by CommandLockFactory (compile classpath)
```

### 4.6 Lifecycle / shutdown

Add an explicit `@PreDestroy` (so the container reclaims the pool even if `stop()` is not called),
and make `stop()` delegate to it. Ordering:

1. `started = false` (stop accepting; guards `start()` idempotency).
2. `queue.close()` — stop the poll thread (no new dispatch).
3. `pool.shutdown()` then `awaitTermination(bounded, e.g. 30s)` — let in-flight workers finish,
   write terminal state, and release their own locks. If it times out, `pool.shutdownNow()`.

Because in-flight workers release their locks in their own `finally`, a graceful drain leaves no
lingering keys. A hard `shutdownNow()` interrupts handlers; the entries stay unacked and the locks
expire by TTL → picked up elsewhere (at-least-once). Micronaut destroys beans in reverse creation
order; `CommandServiceImpl`'s `@PreDestroy` runs before the `JedisPool`/lock beans it depends on.

## 5. Behavioural / contract notes (all backward compatible)

- **External-job pattern preserved.** `execute()` returning `CommandResult.running()` + a
  `checkStatus()` override remains fully supported (see the "slow" integration test). The new
  worker pool changes only *where* these run (off the poll thread), not the semantics.
- **`checkStatus()` now runs on the worker pool** instead of synchronously on the poll thread
  (non-blocking) — an improvement, not a break.
- **Very fast commands** now transition `SUBMITTED → RUNNING → SUCCEEDED` (they briefly pass
  through `RUNNING`), where before a sub-timeout command could skip `RUNNING`. Matches the
  documented state machine; add a one-line changelog note as downstream pollers may now observe
  `RUNNING`.
- **`cancel()` is best-effort / advisory.** It writes `CANCELLED` but does **not** interrupt a
  running handler (matching current behaviour). The worker's terminal write is guarded by a
  re-read (won't overwrite a `CANCELLED` observed before the write), but a `cancel()` landing in
  the narrow gap between the re-read and the write can still be overwritten by a completing worker.
  This residual race exists in the current code too and is accepted; documented here.
- **Per-command cost increases**: each command now does `tryAcquire` (one `SET NX`) + a `RUNNING`
  write + a terminal write + a deferred ack, versus the old inline fast path. This is the price of
  non-blocking parallelism + single-runner and is accepted. Skipping the lock for
  complete-on-first-delivery commands is a possible future optimization (reopens a double-exec
  window; deferred).
- **Local (non-Redis) mode is effectively serial for intake.** `LocalMessageStream.consume()`
  sleeps 1s on the poll thread and re-offers on `false`, so each in-flight command costs a 1s
  poll-thread stall and there is no real reclaim/idle concept. The non-blocking benefit and the
  single-runner guarantee are Redis-mode properties; concurrency assertions must use Testcontainers.

## 6. Test plan

**Unit (Spock, mocked `store`/`lockManager`/pool):**
1. Terminal state on delivery → returns `true`, no dispatch.
2. Absent state → returns `true`.
3. No handler → saves `FAILED`, returns `true`.
4. Fresh `SUBMITTED`, lock acquired → adds in-flight, submits `EXECUTE`, returns `false`.
5. Lock held elsewhere (`tryAcquire` → null) → returns `false`, in-flight NOT left populated, no dispatch.
6. Already in-flight (`add` returns false) → returns `false`, no `tryAcquire`, no dispatch.
7. Pool saturated (`execute` throws `RejectedExecutionException`) → lock released, in-flight removed, returns `false`.
8. `RUNNING` state + handler with `checkStatus` override → dispatches `CHECK_STATUS`.
9. `RUNNING` state + in-process handler (no override) → dispatches `EXECUTE` (crash re-run).
10. `runCommand` EXECUTE happy path → `RUNNING` then `SUCCEEDED`; in-flight cleared; lock released.
11. `runCommand` EXECUTE returns `running()` → stays `RUNNING`, no terminal write, lock released, in-flight cleared.
12. `runCommand` handler throws → `FAILED`; lock released; in-flight cleared.
13. `runCommand` sees `CANCELLED` landed before the terminal re-read → does not overwrite it.
14. Fast worker completes before `processCommand` finishes → assert in-flight ends empty (no leak) and a subsequent terminal delivery acks.
15. **Bean resolution:** a Micronaut `ApplicationContext` (no `seqera.lock.*`) resolves exactly one `@Named(COMMAND_LOCK) LockManager`.
16. `@PreDestroy`/`stop()` terminates the pool (assert `pool.isTerminated()`), draining an in-flight worker first.

**Existing local tests (regression — must stay green):** the full `CommandServiceTest` including the
"slow"/`checkStatus` scenario and `cancel`.

**Integration (Testcontainers Redis, via `lib-fixtures-redis`; mirror an existing Redis-backed Spock
test in a sibling module for setup):**
17. Long in-process handler does **not** block intake: submit a long command then a short one; the
    short one completes while the long one is still running.
18. **Single-runner across two replicas:** two manually-wired `CommandServiceImpl` instances sharing
    one `JedisPool` + one `RedisStreamConfig` (same stream name, same consumer group, distinct
    consumer names) + one shared Redis lock keyspace, with a **small** `claimTimeout` and a handler
    that increments an `AtomicInteger` and sleeps past the claim timeout. Wrap each replica's
    `LockManager` in a counting delegate; assert `execute()` ran **exactly once** AND the second
    replica's `tryAcquire` was invoked and returned `null` (proves lock-mediated exclusion, not mere
    non-delivery).
19. **Crash recovery:** run a long in-process command on instance A; simulate death by abandoning A
    **without** releasing its lock (e.g. do not call `stop()`; stop A's watchdog scheduler / drop the
    instance) so the key expires by TTL; assert instance B reaches a terminal state, only after ~lockTTL.
20. **Ack-on-reclaim (simplification #2):** after a command reaches terminal, assert stream length
    returns to 0 within ~`claimTimeout`.

If Docker is unavailable, tests 17–20 are skipped (documented) while unit + local tests still validate
the logic and no regression.

## 7. Files touched

- `CommandServiceImpl.java` — replace inline `executeWithTimeout` with non-blocking dispatch +
  `runCommand`; add pool, `inFlight` set, lock, checkStatus-override cache, `decideTask`; `@PreDestroy` + `stop()` drain.
- `CommandConfig.java` — add 3 default config methods; deprecate `executeTimeout()` with updated Javadoc.
- `CommandHandler.java` — update Javadoc (the "1 second" note).
- `CommandLockFactory.java` — **new**.
- `build.gradle` — add `lib-lock`, `lib-lock-redis`, `lib-activator`, `redis.clients:jedis:5.1.4`.
- Tests — new unit specs (§6.1–16) + new Redis integration spec (§6.17–20); keep existing local specs green.
- `README.md` / `changelog.txt` / `VERSION` — updated on release (separate step, not part of impl).
