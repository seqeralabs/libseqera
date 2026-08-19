# Command State Write Mutex — Stop Losing Concurrent `CommandState` Writes

Status: implemented — #895 (originally proposed here; #897 folded in, see §7)
Author: Paolo Di Tommaso
Date: 2026-07-31
Stacked on: #890 (ordered shutdown / vendored command queue)

---

## 1. Problem

Every `CommandState` transition is a read-modify-write against Redis, and every write is
unconditional:

```java
// CommandServiceImpl — 7 call sites
store.save(state.started());
store.save(state.clearErrors());
store.save(state.applyResult(result));
store.save(state.withError(rootMessage(e)));   // recordError
store.save(state.failed("No handler for type: " + state.type()));
store.save(state.cancelled());                 // cancel()
```

`state` is a snapshot read earlier — in the dispatch path, potentially seconds earlier. Two writers
that read the same snapshot both write it back in full, so the later write silently discards the
earlier one. There is no version, no conditional write, and no lock.

### 1.1 The case that matters

`cancel()` reads the state, checks it is non-terminal, and writes `cancelled()`. Concurrently the
dispatcher can be finishing the same command and writing `applyResult(...)`. Whichever lands second
wins, so either:

- the cancel is **silently swallowed** — the API returned `true`, the caller believes the command is
  cancelled, and it completes anyway; or
- the terminal result is **overwritten by the cancel** — the command reports `CANCELLED` although
  its work succeeded.

The same shape applies between `recordError` / `clearErrors` and any concurrent transition, and it
gets worse once an execution can finish late (#890's follow-ups), because the gap between reading
`state` and writing it back widens from milliseconds to the whole handler duration.

### 1.2 Why this was previously written off

An earlier review concluded this was unfixable without a new store primitive. That was wrong. The
inherited API does lack a compare-and-swap:

```
StateStore:  get / put / put+ttl / putIfAbsent / putIfAbsent+ttl / remove / clear
```

and `AbstractStateStore.delegate` is `private`, so a subclass cannot reach the provider. But
**`CommandStateStoreFactory` is handed the `StateProvider` directly**, and `CommandStateStoreImpl`
is vendored in this repo (#890), so it can keep its own reference to it.

And CAS is not required: a safe read-modify-write needs **mutual exclusion**, and
`putIfAbsent(key, value, ttl)` + `remove(key)` is exactly that.

## 2. Design

Add one method to `CommandStateStore`, and make it the only way to mutate an existing state:

```java
boolean update(String commandId, UnaryOperator<CommandState> mutator);
```

Implemented in `CommandStateStoreImpl` with a short-lived Redis mutex:

1. `putIfAbsent(cmd-state/v1/lock:<id>, token, stateLockTtl)` — fails fast if another writer holds it.
2. `get(id)`; return `false` if the command is gone or already terminal.
3. `put(id, mutator.apply(current))`.
4. `remove(lock)` in a `finally`.

Contended acquisition retries briefly before giving up, so ordinary contention is invisible to
callers rather than surfacing as a failed write.

Rejecting a transition from a terminal state is essential even after the re-read: the transition
methods themselves are unconditional, so allowing the mutator to run would merely serialize the
original last-writer-wins race. Once `SUCCEEDED`, `FAILED`, or `CANCELLED` is stored, no later
transition can replace it.

### 2.1 Why a mutex is sufficient here, when a lease would not be

The same primitive is unsafe for long critical sections and safe for short ones. This matters
because #890's follow-up work considers a *lease* over an entire handler execution, and the two
must not be conflated:

| | Execution lease (not this change) | Write mutex (this change) |
|---|---|---|
| Critical section | the whole handler run — minutes for `ClusterCreateHandler` | one GET + one SET — sub-millisecond |
| TTL must exceed | unknown, unbounded handler duration | ~nothing |
| Expiry mid-section | plausible; needs refresh, and refresh cannot be fenced | needs a multi-second stop-the-world pause between two adjacent Redis calls |
| Cost of failure | duplicated cloud resources | one lost state write |

So a lease spanning minutes without a fencing token is genuinely fragile, while a mutex spanning a
fraction of a millisecond with a seconds-long TTL is sound in practice. This change is only the
second column.

### 2.2 Residual limitation

This is a lock without fencing: if a writer stalls for longer than `stateLockTtl` *between* acquiring
and writing, a second writer can acquire and both proceed. That requires a GC pause longer than the
TTL landing between two adjacent Redis calls, and it costs a lost write rather than duplicated
infrastructure.

The release is unconditional for the same reason: `StateProvider` has no compare-and-delete, so
`remove(lockKey)` cannot check ownership. A holder that stalled past the TTL therefore releases the
*next* holder's lock on its way out, letting a third writer in — the same stalled-past-TTL trigger
as above, one extra lost write in the worst case. The lock value is a per-acquisition token, so the
overlap is at least visible in diagnostics; conditioning release on it needs the same upstream
primitive as fencing.

Making it airtight means adding a Lua-backed `replaceIf(key, expected, value)` to
`lib-data-store-state-redis` upstream. That is now a nice-to-have rather than a blocker, and is
deliberately out of scope.

### 2.3 Making the safe path the only path

`save()` is kept for the one genuine *create* (`submit()`), and its javadoc states that it
overwrites unconditionally and must not be used to transition an existing command. All seven
transition sites move to `update()`. Putting the lock inside the store rather than at the call
sites means a future transition cannot silently bypass it.

## 3. Change list

### `lib-cmd-queue-redis/.../store/CommandStateStore.java`
Add `update(String, UnaryOperator<CommandState>)`; document `save()` as create-only.

### `lib-cmd-queue-redis/.../store/CommandStateStoreImpl.java`
Retain the `StateProvider`; implement `update()` as above; add `LOCK_PREFIX` and derive the retry
bound from configuration.

### `lib-cmd-queue-redis/.../CommandConfig.java`
Add `stateLockTtl()` (5s) and `stateLockWait()` (100ms) as defaults, alongside the existing
`pollInterval` / `executeTimeout` / `stateTtl`. The lock lifetime and the total contended wait are
operator-tunable; the 20ms retry slice stays a private constant, and the attempt count is derived
from the wait — `attempts × interval` is one property and must not become two settings.

`state-lock-wait` must stay well below `state-lock-ttl`: with `wait < ttl` a contended transition
fails fast and is retried from the queue, whereas with `wait >= ttl` the caller would block until a
stalled holder's lock expires, which is worse for a queue consumer. Documented, not enforced.

### `sched-app` — `SchedCommandConfig` + `application.yml`
Expose both as `sched.command-queue.state-lock-{ttl,wait}`.

### `lib-cmd-queue-redis/.../CommandServiceImpl.java`
Convert the seven transition sites. Failure semantics per site:

| Site | On `update` == false |
|---|---|
| `cancel()` | return `false` — the caller is told the cancel did not take, instead of being told it did |
| terminal `applyResult` | log, `return false` — message stays queued; state was not written, so a retry is consistent |
| `started()` (both sites) | `markRunning()` (added by the #890 review) — retried once, then a loud warn unless the refusal was the terminal guard. RUNNING is what routes the next delivery to `checkStatus()`, so a silently missed mark re-runs `execute()`: against an execution abandoned by the timeout, or against an async job the handler already reported RUNNING |
| `clearErrors()` | ignore — best-effort bookkeeping |
| `recordError()` | ignore — already documented as best-effort |
| no-handler `failed()` | log, `return false` — retried rather than acked with unwritten state |

## 4. Tests

`CommandStateStoreUpdateTest`:

1. **Concurrent updates do not lose a write** — two threads each increment `errorsCount`; assert the
   final count is 2. This is the load-bearing test: it fails against blind `save()`.
2. `update` applies the mutator and persists it.
3. A terminal result cannot overwrite `CANCELLED`.
4. `CANCELLED` cannot overwrite a terminal result.
5. `update` returns `false` for an unknown command id.
6. The lock is released when the mutator throws (a following `update` still succeeds).
7. `update` returns `false` while another writer holds the lock.
8. `update` applies the mutator to the current value, not the caller's stale snapshot.

`CommandServiceDrainTest` (§7): `drain()` stays inside its budget when the dispatcher never
quiesces — 500ms budget, asserts under 3s, where the unfixed path added a further 10s plus a 1s
join.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Lock leaked by a crashed writer | TTL expiry; critical section is two Redis calls |
| Added latency per transition | one extra `SET NX` + one `DEL`; negligible against the work being reported |
| A transition site added later bypasses `update` | `save()` documented as create-only; the lock lives in the store, not at the call sites |
| Contention under load | bounded retry, then the caller keeps the message queued and retries — no lost work |

## 6. Out of scope

- The **execution lease** over a whole handler run (§2.1), which is where the fencing problem
  actually bites. Separate decision, separate PR — it reintroduces leasing to a codebase that
  deliberately reverted it.
- A Lua `replaceIf` upstream in `lib-data-store-state-redis` (§2.2).
## 7. Landed alongside (was out of scope, folded in via #897)

The `drain()` → `close()` budget overlap from #890's review: `drain()` waited for the dispatcher and
then `close()` started a *fresh* `closeTimeout()`, so `drain(20s)` could take ~31s against a 25s
graceful-shutdown grace period and be hard-stopped mid-drain. `close(Duration)` now takes the
caller's budget and `drain()` passes what remains of its own, so there is one number rather than two
to keep aligned.

Implementing that surfaced a further defect: with the budget exhausted, `close(0)` fell straight
through to `thread.interrupt()`, and that interrupt was observed reaching a handler still running on
the executor — the drain cutting short the work it exists to protect. `close()` therefore no longer
interrupts at all. The `closing` flag already guarantees the dispatcher exits at its next loop-head
check, and the thread is a daemon so it cannot hold up JVM exit, so an interrupt only shortened a
wait already abandoned while costing in-flight work and reviving the RESP-desync risk
(libseqera#92).

`drain()` also preserves the result of the dispatcher's initial `awaitQuiescent()` call and reports
an incomplete drain when either the dispatcher is still inside synchronous `checkStatus()` work or
an executor-backed handler remains active. A blocked-`checkStatus()` regression test covers the
dispatcher-only case, which is not represented by the executor's in-flight counter.

## 8. Hardened after the #890 review (`1c34b5683`, `3ef498651`)

- **`markRunning()`** — the §3 `started()` row above: retried once on write-lock contention,
  terminal-aware loud warn on a persistent miss.
- **`submitCounted()`** — the in-flight counter is incremented before `executor.submit()` and
  decremented in the task's `finally`; a rejected submission (executor already torn down) now
  decrements in a catch, since the task never runs. The two decrement sites are mutually exclusive
  — an `execute()` throw surfaces via `Future.get()`, never out of `submit()` — so the count cannot
  go down twice; without the catch a rejection leaks it upward for good and every later `drain()`
  reports false.
- **`AbstractMessageStream.close()` waits only once.** After an explicit drain has spent (or given
  up on) the cooperative wait, the `@PreDestroy` backstop's second `close()` returns immediately
  instead of spending up to `closeTimeout()` again during bean destruction — the same
  one-shutdown-budget argument as §7.
- **The graceful-shutdown delegate no longer blocks.** `CommandQueueGracefulShutdown` triggers the
  drain via `runAsync` and returns the stage: the framework invokes delegates sequentially, so a
  blocking drain serialized the Netty HTTP drain and the readiness flip behind it and made
  `drain-timeout`, not the grace period, the effective bound.
- **`TaskSubmitHandler` permanent-throw guards** — with retry-on-throw active, a missing task row
  returns `CommandResult.failure(...)` and a missing run row cancels the pending task via
  `findRun()`; both were 60s poison loops when thrown.
- **Second-round polish** — the RUNNING-result `started()` site goes through `markRunning()` too
  (it is the sole execute-once guard for a handler that reported an async job in flight);
  `stateLockWait` default dropped 500ms → 100ms (the wait is consumed on the serial dispatcher
  thread, and the critical section it guards is sub-millisecond); the acquire loop no longer
  sleeps after its final attempt; the lock key moved to the `prefix + "/…"` secondary-key
  namespace (`cmd-state/v1/lock:<id>`) so it cannot collide with a state key by construction;
  the lock value is a per-acquisition token rather than a per-store one; and the drain-incomplete
  log names the dispatcher when `activeCommands()` reads zero, instead of reporting
  "0 command(s) still running" during exactly the incident it exists for.
