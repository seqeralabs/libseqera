# Command execution guarantee — message lease (PEL heartbeat) + task-settled delivery

**Status**: **implemented and merged.** The lease, deferred settlement and task-settled delivery shipped in #913; the drain report and the shutdown refusal in #963 (which carried #964). Adversarially reviewed during design (22-agent review, 13 confirmed findings folded in — §Review log).
**How to read this**: §Requirements → §Guarantees describe the machinery as it exists in `CommandServiceImpl` and `RedisWorkQueue` today, and are kept in step with the code. §Lineage, §Alternative considered and §Review log are the archival design record and are deliberately not rewritten.
**Lineage**: selected over two alternatives explored during design — an execution-lock model (per-command lease key in the state store, rejected: a second ownership system beside the PEL, watchdog complexity, and it left crash recovery routed into handler semantics never promised) and a synchronous-dispatch variant of this same ownership model (kept as §Alternative)
**Modules touched**: `lib-data-workqueue` / `lib-data-workqueue-redis` (vendored), `lib-cmd-queue-redis` (vendored), `sched-app` (config + the `QueueMetrics` bean that wires the lease metrics into the DI context). No libseqera changes; no handler changes; no state-schema changes.
**Prerequisite**: the CAS `update()` in `CommandStateStoreImpl` (on this branch), which makes a refused state transition mean terminal-or-missing, never contention.

## Requirements

1. A handler may run longer than the visibility timeout without a second, overlapping
   handler execution for the same command.
2. (Nearly) exactly-once execution.
3. Existing libseqera primitives only. **Handlers stay outside command state
   management** — no recovery contracts, no audits, no new result types.

## First principles

Two design defects in the current model cause everything downstream:

1. **The visibility timeout does two jobs.** It is the *failure detector* (dead consumer
   → redeliver) and simultaneously the *upper bound on handler duration* (anything
   slower is stolen mid-flight). This design removes the second job: a live
   handler's message is heartbeated, so the visibility timeout degrades to its one
   legitimate purpose — detecting a dead consumer.
2. **PROCESSING is dishonest.** Today the queue marks PROCESSING when it loses patience
   (the 1s `execute-timeout`), not when the handler declared async work. That is
   why crash recovery routes into `checkStatus()` semantics handlers never
   promised to support (`ClusterDeleteHandler` polls a READY cluster forever;
   `TaskLaunchBatchHandler` reports success unconditionally). Here PROCESSING only
   ever means *"the handler returned PROCESSING"*: crash recovery is then simply
   `execute()` again — the pre-existing #890 retry-on-throw contract, requiring
   nothing new of handlers.

The execution topology is **unchanged from today**: one dispatcher thread, handler
work on the blocking executor, `submitCounted` and `drain()` as they are. What
changes is who settles the message and when: **the handler task settles its own
entry when it finishes**, and the entry stays leased (heartbeated) for exactly as
long as the task runs. Ownership and execution have the same lifetime, held by the
same object — the PEL entry — so they cannot disagree.

## Mechanism

### 1. The PEL entry is the lease — give it a heartbeat

Redis consumer-group ownership is per-entry mutual exclusion: an entry in a
consumer's PEL is invisible to `XREADGROUP >` and to `XAUTOCLAIM` below the idle
threshold. Its only flaw is the fixed idle clock. `XCLAIM` with `minIdle=0`, the
**same consumer**, and `JUSTID` resets the idle time without incrementing the
delivery counter (Jedis: `xclaimJustId`) — the "still alive, still mine" signal.

`RedisWorkQueue` keeps a **queue-scoped** in-flight registry (composite
keying, mirroring `lastClaimCursor` — a bare `StreamEntryID` key would collide
across queues, since entry IDs are unique only per Redis stream key):

```java
/** Leased entries per queue: registered before the consumer runs, removed on settle. */
private final Map<String, Map<StreamEntryID, Lease>> inFlight = new ConcurrentHashMap<>();
// Lease carries: registeredAt (max-age backstop), the settled flag, the metrics sample
```

One scheduled task per replica renews **per queue, in one round-trip,** at
`fixedRate = lease-renewal-period` (`sched.workqueue.lease-renewal-period`;
when unset it derives as `visibility-timeout / 4`, so the margin math below tracks a
re-tuned visibility timeout automatically — startup fails fast on a period at or
above the visibility timeout). The leak backstop is likewise configurable
(`sched.workqueue.max-lease-age`, derived default `3 × visibility-timeout`):

```java
/** Scheduled at fixedRate = visibilityTimeout / 4. The tick body catches Throwable —
 *  an escaping Error would silently cancel a fixedRate task forever, losing every
 *  lease on the replica at once. */
void renewLeases() {
    final long start = System.nanoTime();
    try {
        inFlight.forEach(this::renewQueue);
    }
    catch (Throwable t) {
        log.error("Lease renewal tick failed", t);           // outer wall; renewQueue contains per queue
    }
    finally {
        final long elapsed = System.nanoTime() - start;
        metrics.renewTick(elapsed, leasedCount());
        if (elapsed > periodNanos) {
            log.warn("Lease renewal tick took {} — exceeds the renewal period; leases at risk", Duration.ofNanos(elapsed));
        }
    }
}

private void renewQueue(String queueId, Map<StreamEntryID, Lease> leases) {
    if (leases.isEmpty()) return;
    try (Jedis jedis = pool.getResource()) {
        // 1. Ownership check, one PIPELINED batch of per-id XPENDING calls (single
        //    round-trip, exact answers): an entry stolen during a renewal outage now
        //    belongs to another consumer. Renewing it blindly (minIdle=0 seizes
        //    regardless of owner) would re-steal it back mid-execution — ownership
        //    ping-pong. Instead: drop it, count it, log it. This makes the residual
        //    duplicate window OBSERVABLE instead of silent.
        //    INVARIANT: an id is renewed only when its ownership was positively
        //    confirmed this tick. A count-capped RANGE query is not safe here: leased
        //    ids bracketing more foreign pending entries than the cap get truncated out
        //    of the response, and treating "missing" as "safe to renew" re-seizes an
        //    entry another consumer legitimately owns. With per-id queries an absent id
        //    has exactly one meaning — no longer pending (acked/deleted): dropped, and
        //    counted as lost when the lease was never settled.
        // 2. Age backstop, liveness-gated: a lease older than 3× the visibility timeout whose
        //    owner is not provably alive (MessageLease.bindLiveness — the dispatcher
        //    binds the handler task's Future) is a registry leak (a settlement path
        //    that never ran) — stop renewing, log loudly, let the claim cycle recover
        //    the entry. A leak can never be permanent, and a live handler is never
        //    age-pruned however long it runs (requirement 1) — an unconditional age
        //    prune would re-introduce the steal-mid-flight bug class at 3× the old
        //    threshold for any handler slower than 3×VT.
        // 3. One variadic XCLAIM JUSTID for everything still ours — a single round-trip
        //    per queue per tick, so tick duration does not scale with in-flight count
        //    (25 sequential renewals against a slow-but-alive Redis would exceed the
        //    visibility timeout and lose every lease exactly when Redis degrades).
        final List<StreamEntryID> mine = checkOwnershipAndPrune(jedis, queueId, leases);   // XPENDING
        if (!mine.isEmpty()) {
            jedis.xclaimJustId(queueId, config.getDefaultConsumerGroupName(), consumerName,
                    0, new XClaimParams(), mine.toArray(StreamEntryID[]::new));
        }
    }
    catch (Exception e) {
        // Transient: the next tick retries; see the margin math below.
        metrics.renewError();
        log.warn("Lease renewal errored for queue {}, will retry", queueId, e);
    }
}
```

**Margin math, stated honestly.** Writing `VT` for the visibility timeout: with
period `P = VT/4` (5s for the shipped
VT=20s) and a successful renewal at `t0`, the entry becomes claimable at
`t0 + VT`. Ticks fire at `t0+P, t0+2P, t0+3P` — so **two consecutive failed or
missed ticks are tolerated with a `VT/4` margin remaining**; the third strike
loses the lease. The safe outage tolerance is `VT − 2P − tick-latency` (~10s at
VT=20s), and the tick latency is bounded by design (two round-trips per queue,
not per entry). A replica crash wipes the in-memory registry → heartbeats stop →
the entry idles past the visibility timeout → redelivered elsewhere: exactly the
failure-detection semantics the visibility timeout was for.

**Why VT=20s ships as the default.** The lease decouples the visibility timeout from
handler duration, so it is tuned purely for what it still governs: crash-recovery
latency and the transient-error retry cadence — both improve 3× versus the
previous 60s (a dead replica's work recovers, and a thrown handler retries, in
~20s). The PROCESSING re-poll cadence is deliberately NOT coupled to it: re-polls
pace on `check-status-interval` (45s — slightly tighter than the pre-lease 60s:
~25% faster discovery for ~33% more polls) via the delayed retry
below, so tightening crash detection does not multiply the `checkStatus()` load
on the DB/cloud reads every poll performs (with a PROCESSING `TaskSubmitHandler`
polling for the whole task lifetime, pollers ≈ in-flight tasks — PR #913
review). The cost of the shorter visibility timeout is the narrower renewal-outage
tolerance above (~10s of Redis brownout instead of ~30s) before the residual
duplicate window re-opens — observable (`lease-lost` counter), damage bounded
by the CAS terminal write. Tune via `sched.workqueue.visibility-timeout` if
brownout tolerance ever matters more than recovery latency.

**Delayed retry (`MessageLease.retryAfter`).** The visibility timeout would otherwise
still do two jobs — failure detection AND the re-poll cadence — the same
dual-role defect this design removed for handler duration. `retryAfter(delay)`
completes the decoupling: the lease stays registered (renewed, unstealable)
until `delay − VT` elapses, then the renewal tick releases it and the
natural idle-out delivers at ≈ the requested delay. A held lease is settled
(late `ack()`/`retry()` are no-ops), is deliberately excluded from the leak
backstop, and a delay at or below the visibility timeout degrades to a plain
`retry()` — the visibility timeout is the effective cadence floor.

**Metrics** (all in the queue layer, Micrometer via the existing
`QueueMetrics` hook): leased-entries gauge, max-lease-age gauge, renewal-tick
timer, renewal-error counter, lease-lost counter (ownership check), lease-leak
counter (age backstop), and a renewal-liveness gauge (`lease.renewal.age`,
seconds since the last COMPLETED tick — the stuck-tick detector; alert above a
few renewal periods).

**Bounded pool borrows (PR #913 review).** The Throwable containment above only
covers ticks that FAIL — a tick that BLOCKS is worse: the Jedis pool's
commons-pool2 default (`maxWait=-1`) waits indefinitely on an exhausted pool, so
a blocked borrow inside the tick never throws (no `renewError`, no overrun warn
— it lives in a `finally` that never runs) and the single-threaded
`scheduleAtFixedRate` never starts the next tick: every lease on the replica
goes stealable one visibility timeout later with zero telemetry, precisely during
the pool-exhausting brownout when leases matter most. Fixed at the source:
`lib-jedis-pool` 1.2.0 exposes `redis.pool.maxWait` and sched sets it to 2s —
below the 5s renewal period, so a starved tick fails loudly and the next one
runs on schedule (every borrower in this codebase is retry-safe). The
`lease.renewal.age` gauge is the belt for stuck-tick causes not yet imagined. `RedisWorkQueue` injects an *optional*
`QueueMetrics`, so production wiring is explicit: sched-app provides the bean
(`SchedCommandQueueFactory.queueMetrics`, present whenever a `MeterRegistry`
is) — without it every lease metric would silently be a no-op.

### 2. Deferred settlement — the consumer API change

The current contract (`boolean accept(msg)`: true = ack now, false = leave
pending) cannot express "a task now owns this entry". It becomes (named
`Decision`, not `Outcome` — the metrics package already owns that simple name):

```java
public interface MessageConsumer<M> {

    enum Decision {
        ACK,        // settle now: remove from the queue
        RETRY,      // leave pending: redelivered after the visibility timeout
        DEFERRED    // a task owns the lease; it will settle via MessageLease
    }

    Decision accept(M message, MessageLease lease);

    /** Admission gate: the dispatcher does not claim from this queue while false. */
    default boolean ready() {
        return true;
    }
}

/** Handle to settle a DEFERRED entry from any thread. Idempotent: first call wins. */
public interface MessageLease {
    void ack();      // stop renewal, then XACK + XDEL best-effort
    void retry();    // stop renewal only; the entry re-claims after the visibility timeout

    /** Age-backstop gate: while the bound probe reports the owning task alive, the
     *  lease is never pruned as a leak. Default no-op (queues without renewal). */
    default void bindLiveness(BooleanSupplier alive) {}
}
```

Settlement rules the implementation must honor (review findings #4, #6, #9):

- **Registration bracket**: `consume()` registers the entry *before* invoking
  the consumer and un-registers on every path except a *returned* `DEFERRED` —
  including a thrown exception, which settles as RETRY. Only the DEFERRED return
  value transfers the lease to the task; nothing else may leave an entry
  registered.
- **Un-register first, Redis second**: `ack()` flips the settled flag and
  removes the entry from the registry *before* attempting `XACK`+`XDEL`. A
  failed `XACK` then degrades to the benign case — the idle clock resumes, the
  entry redelivers, and the delivery acks on the terminal check — instead of a
  heartbeated-forever orphan. `retry()` is registry-removal only; **stopping
  renewal is the release**, and the claim cadence is the retry schedule — no
  Redis call at all.
- **Idempotence**: an `AtomicBoolean` on the lease; late `ack()`/`retry()` calls
  are no-ops. `XCLAIM` without `FORCE` cannot resurrect an acked entry, so a
  renewal racing an ack is harmless. (Note the scope of that claim: it covers
  the *post-ack* race only. The *post-steal* case — another consumer now owns
  the entry — is handled by the ownership check in `renewQueue`, not by XCLAIM
  semantics.)

`LocalWorkQueue` mirrors the semantics in-memory (a DEFERRED message is
unavailable until its lease settles; `retry()` re-queues it with a redelivery
delay (`workqueue.local.retry-delay`, default 1s) — the local analog of the claim
cadence, deliberately much shorter since local is a dev/test profile. Without
it, a consumer retrying fast (a handler repeatedly declaring PROCESSING, or
throwing quickly) would drive a hot loop of continuous redeliveries in
non-Redis deployments. The dispatcher reinforces the same pacing generically:
only ACK/DEFERRED count as progress for the idle-pause decision, so a
retry-only cycle sleeps the poll interval — on the Redis path this is harmless
(the entry is idle-gated anyway). The local provider still has no claim clock,
which tests must not
assume). sched is the only consumer of the vendored queue, so the API change
has exactly one call site to migrate.

**Metrics mapping**: the claim-time sample travels on the lease; `ACK` records
`processed`, `RETRY` records `active`, a consumer throw records `errored` — at
decision time for synchronous decisions and at settle time for DEFERRED, so the
processing timer keeps measuring real handler duration. A `ready()==false` skip
records a `saturated` counter (not `EMPTY` — an admission-blocked replica under
backlog must be distinguishable from an idle one).

### 3. Dispatch always — `executeWithTimeout` is deleted

`CommandServiceImpl` submits every handler invocation to the blocking executor
and returns `DEFERRED` immediately; the dispatcher never waits on a handler.
The 1s budget, the abandoned-execution model, its discarded results, and the
patience-driven `markProcessing` are all deleted.

```java
/**
 * Queue consumer entry point. Deliveries settle three ways: ACK for stale/terminal
 * messages, RETRY for transient refusals (redelivered after the visibility timeout),
 * DEFERRED when a handler task takes the entry lease and settles it on completion.
 * A throw out of this method is settled as RETRY by the queue layer.
 */
private MessageConsumer.Decision processCommand(CommandMsg msg, MessageLease lease) {
    final var state = store.findById(msg.commandId()).orElse(null);
    if (state == null) {
        log.error("Command state not found - this should not happen: id={}", msg.commandId());
        return Decision.ACK;
    }
    if (state.status().isTerminal()) {
        return Decision.ACK;
    }
    final var registration = getHandler(state.type());
    if (registration == null) {
        log.error("No handler for command type: {}", state.type());
        return store.update(state.id(), s0 -> s0.failed("No handler for type: " + s0.type()))
                ? Decision.ACK
                : Decision.RETRY;
    }
    return dispatchCommand(state, registration, lease);
}

/**
 * Hand the delivery to a handler task. From a successful submit onward the TASK owns
 * the lease — runCommand() settles it on every exit path. A rejected submit means
 * nothing runs and nothing owns the lease: RETRY, the claim cycle re-delivers.
 */
private <P, R> MessageConsumer.Decision dispatchCommand(
        CommandState state,
        CommandRegistration<P, R> registration,
        MessageLease lease) {

    final Command<P> command = toCommand(state, registration);   // a throw here → RETRY via the queue layer
    final CommandHandler<P, R> handler = registration.handler();
    try {
        final Future<?> task = submitCounted(() -> runCommand(command, state, handler, lease));
        // the age backstop never prunes a live task's lease (see §1, backstop gate)
        lease.bindLiveness(() -> !task.isDone());
    }
    catch (RuntimeException e) {
        log.error("Command dispatch rejected, will retry: id={}", state.id(), e);
        return Decision.RETRY;
    }
    return Decision.DEFERRED;
}

/**
 * Runs on the blocking executor; the entry lease is renewed for as long as this
 * takes, so a slow handler can never be stolen mid-flight. The finally guarantees
 * every exit — including an Error out of handler code — settles the lease; the
 * idempotent settle makes the happy-path ack and the finally's retry compose.
 */
private <P, R> void runCommand(Command<P> command, CommandState state,
                               CommandHandler<P, R> handler, MessageLease lease) {
    try {
        // Terminal snapshot: unreachable via processCommand()'s pre-check, but this method
        // stays total rather than trusting its caller — the message is stale: ack. On its
        // own branch BEFORE routing, so no handler result can ever be confused with it.
        if (state.status().isTerminal()) {
            lease.ack();
            return;
        }
        // Shutting down: this delivery was claimed before the shutdown began, and starting
        // the handler now can only add work the drain has to wait out — one cloud call can
        // carry a budget longer than the whole drain budget, so it cannot be waited out at
        // all. Nothing is mutated at this point, so there is nothing to roll back and
        // nothing to protect by proceeding. Settled as a retry: redelivered on the claim
        // cadence — to a replica that is not shutting down, or to this process after a
        // restart — and it runs from exactly this state. Checked AFTER the terminal branch,
        // so a stale message is still acked here rather than pushed into the next process.
        if (draining) {
            log.debug("Command not started - service is draining: id={}, status={}", state.id(), state.status());
            lease.retry();
            return;
        }
        // Route explicitly on the snapshot status, every value named: reaching a terminal
        // arm is a routing bug, and an unmapped new status must never silently execute —
        // both throw, landing in the retry-on-throw catch below.
        final CommandResult<R> result = switch (state.status()) {
            // PROCESSING is the handler's own earlier declaration (an execute() that returned
            // PROCESSING) — never the queue's impatience — so checkStatus() is only invoked on
            // the async-work pattern it was written for.
            case PROCESSING -> handler.checkStatus(command, state);
            // PENDING executes; after a crash the state is still PENDING, and the lease
            // guarantees the crashed invocation is not still running on a live replica.
            case PENDING -> handler.execute(command);
            case SUCCEEDED, FAILED, CANCELLED -> throw new IllegalStateException("Terminal status must be acked before routing - id=" + state.id());
            default -> throw new IllegalStateException("Unmapped command status: " + state.status());
        };
        // A null result is a handler bug and must stay RETRYABLE — never a stale-message
        // sentinel: acking would remove a live command's only message, stranding it with no
        // retry driver left.
        Objects.requireNonNull(result, () -> "Handler returned a null command result - id=" + state.id());

        if (result.status() == CommandStatus.PROCESSING) {
            // The handler declared async work in flight: record the declaration, then
            // schedule the next checkStatus() poll on the re-poll cadence — decoupled from
            // the visibility timeout, which paces crash detection and error retries. Tightening
            // that clock must not multiply the polling load.
            recordProcessingDeclaration(state);
            lease.retryAfter(config.checkStatusInterval());
            return;
        }

        // Terminal result, recorded via the CAS update: a refusal means the command went
        // terminal underneath (a cancel won) — the redelivery acks on the terminal
        // check, so RETRY loses nothing.
        if (store.update(state.id(), s0 -> s0.applyResult(result))) {
            log.debug("Command completed: id={}, status={}", state.id(), result.status());
            lease.ack();
        }
        else {
            // Two very different causes hide behind a refused terminal write and must not
            // read as one: a VERIFIED terminal (or missing) state means a cancel or expiry
            // won underneath — drop the result and ack the stale message now; anything else
            // is the theoretical exhaustion of the CAS retry bound against a LIVE command —
            // retried via redelivery, loudly.
            settleUnrecordedResult(state, lease);
        }
    }
    catch (Exception e) {
        // Retry-on-throw (#890), now uniform: the state is unchanged, so the redelivery
        // re-executes a PENDING command or re-polls a PROCESSING one. No rollback needed:
        // there is no queue-invented PROCESSING to roll back.
        log.error("Command processing errored, will retry: id={}", command.id(), e);
        recordError(state, e);
        lease.retry();
    }
    finally {
        // Backstop for non-Exception Throwables (OOME, NoClassDefFoundError out of
        // handler code): a lease must never outlive its task. Idempotent — a no-op
        // when a branch above already settled.
        lease.retry();
    }
}
```

### 4. Admission cap — the throttle the 1s budget used to be

Today the 1s dispatcher wait implicitly throttles claiming. With fire-and-submit
it must become explicit, or a backlog flood spawns unbounded tasks:

```java
// CommandServiceImpl — MessageConsumer.ready()
@Override
public boolean ready() {
    return inflight.size() < config.maxConcurrency();
}
```

`inflight` is the in-flight **register** (§5), not a bare counter — its size *is* the
count, so the admission cap, `activeCommands()`, the drain wait and the shutdown
report cannot disagree with each other. `size()` on a concurrent map is an estimate
rather than a linearizable count, which costs nothing here: the only inserter is the
dispatcher thread that reads it, so it always observes its own insert, and the only
staleness comes from other threads' removals — which reads *high* and makes the cap
admit fewer, the safe direction for the pool the cap exists to protect.

`AbstractWorkQueue.processMessages()` skips a queue whose consumer is not
`ready()` (recorded via the `saturated` counter; an idle loop still pauses on
the poll interval). Note the drain ceiling at saturation: a not-ready dispatcher
sleeps the FULL poll interval before re-checking, so backlog drain is additionally
bounded at ~`max-concurrency / poll-interval` admissions (~20/s at the defaults)
regardless of how fast handlers finish — raising the cap alone buys
proportionally less than it looks for fast handlers; lower `poll-interval` too
if backlog drain rate ever matters. New config
`sched.command-queue.max-concurrency` (default 20 —
sized below the shared JDBC pool minus request-path headroom, so handler bursts
queue in the work queue rather than starving the API/health probe of connections;
the pool was raised to 35 alongside, PR #913 review),
replacing `execute-timeout`, which is deleted. This closes the "no in-flight
admission cap" gap documented in `application.yml` since the #685 revert.

### 5. The in-flight register, and what a drain reports (#963)

`inflight` is a `Map<Long, String>` keyed by a **monotonic sequence**, valued by
`<commandId>(<type>)`. Keying on the sequence rather than the command id matters more
than it looks: two invocations of one command in flight at once (not expected under the
lease, but not structurally impossible) must not collapse into a single entry — that
would under-count the admission cap and the drain, not merely lose a name. The entry is
registered in `submitCounted` before the task runs and unregistered on every exit path,
including a rejected executor submit; the two removal sites are mutually exclusive, so a
submission can never remove twice.

`drain(Duration)` arms the shutdown signal, stops the dispatcher claiming
(`awaitQuiescent`), waits for the register to empty, then releases the queue — bounded
by what is left of the caller's budget, so `close()` cannot start a second timer of its
own and push the drain past the container's grace period.

The first two waits **share** the budget, and the deadline for the second is taken before
the first runs — so whatever the dispatcher wait spends comes out of the register wait's
share. `quiesceBudget()` therefore caps the dispatcher wait at a quarter of the budget.
Handed all of it, a dispatcher that never quiesces leaves the register wait with nothing:
its loop runs zero iterations and handler tasks mid-flight against the database get no
grace at all, which is the one thing the drain exists to provide (#888). A quarter derives
from the caller's budget the way the lease renewal period derives from the visibility
timeout, so re-tuning `drain-timeout` scales both halves and there is no second dial to
keep in step. It is generous by construction: the dispatcher's own work between two
loop-head checks is a `findById` and a dispatch decision, since every handler invocation
runs on the executor and a delivery claimed after the shutdown began is not routed at all.

The cap does not distort the report. `close()` waits again with the leftover budget, so a
dispatcher that outlives its quiesce budget but stops before the drain budget expires is
*slow, not stuck* — the drain re-probes after `close()` returns and reports success, with
a dedicated warning naming the exceeded quiesce budget. Only a dispatcher still running at
the end of the whole budget reports `dispatcherStopped=false`, exactly as before the cap.
Re-probing the dispatcher is safe where re-reading the register (below) is not: a stopped
dispatcher stays stopped.

The report is taken from **one read at the deadline**, before the queue is released:

```
WARN CommandServiceImpl - Command service drain incomplete - dispatcherStopped=true,
     activeCommands=2, inFlight=[cmd-0abc(task-submit), cmd-0def(cluster-create)], timeout=PT20S
```

Two reads could describe two different instants, and re-reading after `close()` would be
worse than untidy: `close()` can consume what is left of the budget, so a task that
outlived the deadline but finished during it would make the later read empty and the
caller would be told the drain succeeded. For the same reason the register is **not**
published on the `CommandService` interface — a caller re-reading it after `drain()`
returned would race the very work being reported. `CommandQueueGracefulShutdown` records
the budget that expired and points at this line.

**What the drain actually costs, measured.** 30 days of production (2026-07-14 → 08-12):
**12 drain events, 10 clean, 2 incomplete.** Every clean drain finished in **13–52 ms** —
the wait loop exits on its first poll with the register already empty. So the drain is not
a rollout-latency cost, and `drain-timeout: 20s` is a ceiling that is essentially never
approached. Both incomplete drains reported `dispatcherStopped=false` with the *dispatcher*
failing to quiesce inside the budget, not a handler — those were pre-#913 pods where
`checkStatus()` ran synchronously on the dispatcher thread. That cause is gone, but the
*shape* is now bounded whatever the cause: with the dispatcher wait capped at a quarter, a
dispatcher that never quiesces costs 5s of the 20s and the register wait still gets 15s,
instead of being skipped entirely.

**Rejected: an age-based "drainable work" predicate.** Making the wait exit on "no
in-flight invocation younger than N" was implemented and withdrawn. Two reasons, both
worth recording so it is not re-proposed: the measurement above shows there is no wait to
shorten, and `drain-timeout` is documented as sized against the worst observed batch launch
(~7s), so any such cap has to sit above ~10s to avoid abandoning the very work #888 created
the drain to protect — leaving it to save ~5s on the minority of rollouts that have
anything in flight at all. Abandoning an invocation is also not free: a handler that
catches broadly and records a terminal result can settle the command in Redis while its
domain rows stay PENDING.

### What stays, and one honest delta

- Single dispatcher thread; `pollInterval` idle backoff; the exponential error
  backoff; `check-status-interval` (default 45s) as the poll clock for handler-declared
  PROCESSING — transient-error retries and crash recovery stay on the claim cadence.
- The blocking executor, `submitCounted`, the `drain()` wait-then-release shape,
  `markProcessing` (single retry + verify) — now reached through
  `recordProcessingDeclaration`, which distinguishes the *first* declaration (state still
  PENDING: write the transition) from a subsequent poll (state already PROCESSING:
  write-free, except to clear a recovered error streak) — `recordError`, the CAS
  `update()`.
- Handlers: signatures, semantics, and the existing #890 re-executability
  contract. Nothing else is asked of them — the shutdown refusal in §3 is decided at
  the routing boundary, so no handler participates in it.
- **Delta (review finding #8)**: `inflight` — and therefore `activeCommands()`,
  the readiness indicator, `drain()`'s wait and the admission cap — now covers
  `checkStatus` polls too, which previously ran uncounted on the dispatcher
  thread. This is an improvement (drain no longer abandons an in-flight poll),
  but it inverted one drain test's premise: `CommandServiceDrainTest` was rewritten,
  not merely adapted.
- **Since #913**: the bare `inflight` counter became the register described in §5, so
  the drain's count and the identities it reports come from one structure; and `drain()`
  / `stop()` now raise a shutdown signal that `runCommand` consults before routing
  (§3). Both are additive to the model above — no change to ownership, settlement or
  the lease.

## Guarantees

- **No overlap while the owner lives**: ownership and execution share one
  lifetime on one object. A steal requires the idle clock to pass the
  visibility timeout, which a live task's heartbeat prevents — regardless of how long
  the handler runs (requirement 1, verbatim).
- **Crash recovery**: heartbeats stop, the entry redelivers after one claim
  cycle, the state still says PENDING → re-execute. No false SUCCEEDED, no
  unpollable PROCESSING, no handler participation.
- **Nearly exactly-once** — the residual duplicate windows:
  1. Crash redelivery re-executing work whose side effects partially landed —
     the pre-existing at-least-once contract, inherent.
  2. A renewal outage exceeding `VT − 2·(VT/4)` after the last successful tick:
     a steal races a still-live handler. Unfenced by design, but **observable**:
     the ownership check detects the loss on the next successful tick, stops
     renewing (no ping-pong re-seizure), and counts it (`lease-lost`). The CAS
     makes the first terminal result win and the loser's write refuse; external
     side effects in the window can double — closable only with cloud-side
     idempotency.
  3. Duplicate *entries* for one command (re-drive paths, #906) are out of
     scope — deliberately: the normal flow enqueues exactly one entry per
     command, and this model removes the re-drive's reason to exist (an entry
     is never lost: throw → retry, crash → redeliver, completion → ack). If
     evidence ever shows duplicate entries racing live executions, a
     command-keyed gate (Proposal B's mechanism) composes onto this design at
     the task boundary.

## Pros

- One ownership system, zero new keys, zero state-schema change, zero rollout
  hazard, no watchdog-over-a-side-lease. (Deploy note: during a rolling window,
  old-code replicas don't heartbeat — their in-flight work keeps today's
  semantics and stealability; new-code entries are protected. No wire-level
  incompatibility: the Redis stream format is unchanged.)
- Deletes more than it adds: `executeWithTimeout`, the 1s budget, abandoned
  executions and discarded results all go; completions ack immediately with
  their real outcome.
- PROCESSING regains an honest meaning → the crash-recovery handler-contract
  problem class (audits, restart signals) is unreachable by construction.
- Head-of-line blocking disappears (the dispatcher never waits on a handler —
  today it waits up to 1s on execute and unboundedly on checkStatus).
- Concurrency and shutdown semantics preserved, plus an explicit admission cap
  where there was none.

## Cons

- The vendored queue consumer API changes (deferred settlement); both
  `RedisWorkQueue` and `LocalWorkQueue` implement lease semantics.
  Contained: sched is the only consumer.
- Heartbeat rides on Redis health: the quantified outage window above re-opens
  overlap (the "nearly") — now with detection and a counter, not silently.
- The renewal scheduler is a new moving part: Throwable-contained tick, batched
  per-queue round-trips, age backstop, and its own metrics.
- Per-entry (not per-command) exclusion: duplicate entries from re-drive paths
  are out of scope, as argued above.

## Alternative considered — synchronous execution on N dispatcher threads

The earlier draft ran handlers synchronously inside the consumer callback and
scaled with N (virtual-thread) dispatchers. Same ownership model, same
guarantees; rejected in favor of task-settled delivery because the latter keeps
today's execution topology (executor, concurrency under an explicit cap,
`drain()` machinery) and keeps the claim path single-threaded, at the cost of
one contained consumer-API change.

## Essential tests (Redis testcontainer)

1. **The requirement, verbatim**: a handler sleeping well past a shortened
   visibility timeout → a second consumer's `XAUTOCLAIM` finds nothing; exactly one
   execution; the entry acks with the handler's result.
2. **Crash recovery**: kill the heartbeat (simulate replica death) mid-execute →
   entry redelivered after one claim cycle, state PENDING, re-executed exactly
   once elsewhere.
3. **Poll model**: handler returns PROCESSING → lease released, `checkStatus`
   re-polled on the check-status interval (claim cadence when at the floor); a slow
   `checkStatus` is not stolen mid-poll. A delayed retry holds the lease —
   unstealable, never leak-pruned — and redelivers no earlier than the delay.
4. **Renewal margin**: renewal failing for up to two ticks (< VT − 2P total) →
   no steal; renewal recovers. Tick duration independent of in-flight count
   (batched renewal — one pipelined XPENDING batch + one XCLAIM per queue).
   The ownership check stays exact under a large foreign PEL: a stolen leased
   entry bracketed by hundreds of other consumers' pending entries is still
   detected, dropped and counted — never re-seized.
5. **Ownership check**: an entry force-claimed by another consumer between
   ticks → the next tick drops it (lease-lost counter), does NOT re-seize it,
   and the thief's execution proceeds unmolested.
6. **Settlement**: ack from the task thread removes the entry (XACK+XDEL);
   `retry()` leaves it pending; double-settlement is a no-op; a renewal racing
   an ack does not resurrect the entry; a consumer that THROWS settles as RETRY
   and leaves nothing registered.
7. **Admission cap**: with `max-concurrency` saturated the dispatcher stops
   claiming (saturated counter increments); entries drain as tasks finish; no
   unbounded task spawn under a backlog flood.
8. **Retry-on-throw**: a throwing `execute()` → entry redelivered, re-executed;
   errorsCount incremented; no PROCESSING fabricated.
9. **Drain**: in-flight tasks (execute AND checkStatus polls) settle their
   leases before `drain()` returns; leases of tasks that outlive the budget
   stop renewing → redelivered later. The incomplete report is taken at the
   deadline, so a task that finishes during `queue.close()` still counts as
   outliving the budget — a post-close re-read would flip the outcome to
   "drained" and the test asserts it does not.
10. **Age backstop**: a leaked lease (settlement suppressed in test, no live
    owner bound) stops being renewed after 3× the visibility timeout and the entry
    recovers via the claim cycle; a lease whose bound owner is still alive is
    never age-pruned, however old.
11. **Shutdown refusal**: a delivery claimed just before `drain()` — and one
    claimed after `stop()` — is not routed to its handler, writes no state, and
    settles as retry rather than ack; a *terminal* delivery is still acked while
    shutting down (the guard sits after the terminal branch); a restarted service
    routes deliveries again. Driven through the real `drain()`/`stop()`/`start()`
    entry points, so the tests prove the arming and clearing, not merely the guard.
12. **In-flight register**: names the running command by id and type, forgets it
    when the task ends, reports several commands in a stable order, and leaks no
    entry when a task throws or the executor rejects the submit.

## Sequencing — as delivered (#913, branch `command-message-lease`)

1. `lib-data-workqueue` / `lib-data-workqueue-redis`: `MessageLease` + `Decision` consumer API,
   queue-scoped registry, batched renewal with ownership check + age backstop
   + metrics, lease semantics in `LocalWorkQueue`, `ready()` admission and
   metrics mapping in `AbstractWorkQueue`.
2. `lib-cmd-queue-redis`: `processCommand`/`dispatchCommand`/`runCommand` as
   drafted; delete `executeWithTimeout` and the `execute-timeout` config; add
   `max-concurrency`; rewrite `CommandServiceDrainTest`; adapt the remaining
   tests; add the container suite above.
3. `sched-app`: `SchedCommandConfig` + `application.yml` (drop
   `execute-timeout`, add `max-concurrency`, update the model notes).

## Review log

Adversarial review (4 lenses × verification, 22 agents): 13 findings confirmed,
5 refuted. Folded in: queue-scoped registry keying (#1); batched per-queue
renewal + VT/4 period + honest margin math + tick-duration warn (#2, #5, #10);
ownership check preventing re-seize ping-pong + lease-lost observability (#3,
#12); exception-safe registration bracket, unregister-first ack, finally-settle
in runCommand, age backstop (#4, #6, #9); Throwable-contained tick + renewal
metrics (#11, #13); `Decision` naming and metrics mapping incl. saturated
counter (#7, #13); drain-delta honesty + `CommandServiceDrainTest` rewrite (#8).

### After the merge

- **#963** — the in-flight register replaces the bare counter, and an incomplete drain
  names what was running instead of only counting it (§5). Review note folded in: the
  report is read once at the deadline rather than rebuilt by the caller afterwards.
- **#964** (merged into #963) — the shutdown refusal in `runCommand` (§3), plus the
  contract stated on `CommandService.start()` / `stop()` / `drain()`, since for a vendored
  library the interface is what a consumer reads.
- **#965** — closed unmerged. It placed the same "don't start work that cannot finish"
  decision at four hand-placed checkpoints *inside* handler business logic, each needing
  its own rethrow arm ahead of the generic `catch (Exception)`. A missing arm surfaces as
  a task failed with no exit code, and the invariant broke once during the PR's own
  development. The refusal at the routing boundary (§3) covers the same window with no
  handler participation.
- **Prod measurement** (§5) retired levers that this document's #955 follow-up had
  proposed: shortening `drain-timeout`, splitting the in-flight counters, and an
  age-based drainable-work predicate.
