# Design — Async, non-blocking consumer processing in `lib-data-stream-redis`

- **Status:** Accepted (implementation)
- **Date:** 2026-07-11
- **Author:** Paolo Di Tommaso (with Claude Code)
- **Module:** `lib-data-stream-redis` (current `1.5.0` → proposed `1.6.0`)
- **Downstream impact:** `lib-cmd-queue-redis`, `sched`, `wave`
- **Motivates / supersedes:** [seqeralabs/sched#600](https://github.com/seqeralabs/sched/issues/600),
  [seqeralabs/sched#303](https://github.com/seqeralabs/sched/issues/303); reframes and
  subsumes the accept()-scoped heartbeat spec [libseqera#73](https://github.com/seqeralabs/libseqera/issues/73)

## 1. Problem

Message consumption in `AbstractMessageStream` runs on **a single listener thread per
instance**, calling the consumer **inline**. This produces three coupled defects.

### 1.1 Head-of-line blocking (base layer)

One thread (`createListenerThread`, `AbstractMessageStream:148-153`, started in
`addConsumer:222-224`) loops over **all** registered streams and runs
`consumeOne → stream.consume → processMessage → consumer.accept` (`:255-272`, `:242-247`)
**synchronously**. A slow `accept()` on any stream blocks every other stream on that
instance, and only one message is ever in flight per instance.

### 1.2 Duplicate execution — a long `claim-timeout` is unsafe (sched#600)

`RedisMessageStream` reclaims a stalled entry with `XAUTOCLAIM` once it has been idle
longer than `claim-timeout` (`:201-256`), acking only on `accept()==true` (`:155-167`).
`XAUTOCLAIM`'s only liveness signal is idle time, so it **cannot tell a dead consumer
from a slow-but-alive one**. A handler running longer than `claim-timeout` is reclaimed
by a peer and **re-executed while the original still runs** — the sched#600 churn (a
~5 min Azure `launchInstances` on a 60 s `claim-timeout`, re-run cross-pod).

### 1.3 Slow status detection — a short `claim-timeout` is also bad (sched#303)

A not-yet-terminal command never acks, so it is only re-delivered when its idle time
crosses `claim-timeout` — making `claim-timeout` a hard **minimum re-poll interval**.
At 60 s this adds up to a full minute of latency between an external job's real state
change and sched observing it (measured 10–49 s per task), inflating Nextflow's
perceived wall-times.

### 1.4 The vise

1.2 and 1.3 pull `claim-timeout` in opposite directions — #600 wants it long, #303
wants it short — and 1.1 blocks the loop regardless. The three cannot be resolved by
tuning one knob.

## 2. Approach (overview)

Three coordinated changes, shipped together as `1.6.0`:

- **P1 — async dispatch.** The listener thread becomes a *dispatcher*: it hands each
  message to a bounded worker pool and never runs a handler itself → no head-of-line
  blocking, concurrent processing. (Fixes 1.1.)
- **P2 — worker-scoped heartbeat lease.** While a handler is registered as in-flight, a
  daemon renews its Redis entry (`XCLAIM … JUSTID`) so an alive consumer is never
  reclaimed, regardless of duration; the lease lapses on crash → real failover
  preserved. This decouples **processing time** from `claim-timeout` entirely, so
  `claim-timeout` can be set purely for crash-failover. (Fixes 1.2 without forcing a
  short timeout.)
- **Model B — hold-lease + scheduled re-poll.** A not-yet-terminal command keeps its
  lease and is re-invoked in-process after `pollInterval`, instead of waiting for a
  Redis reclaim. This decouples **re-poll cadence** from `claim-timeout`, so status is
  detected within `pollInterval` (1 s) while `claim-timeout` stays long. (Fixes 1.3.)

Net: `claim-timeout` governs **only** crash-failover; processing time and re-poll
cadence are independent of it.

### 2.1 Cadences after this change

| Concern | Knob | Value (sched) | Coupled to handler time? |
|---|---|---|---|
| New-message poll (idle backoff) | `pollInterval` | 1 s | No — dispatcher never awaits a handler |
| Re-poll of a not-yet-terminal command | `pollInterval` *(reused)* | 1 s | No — scheduled while the lease is held |
| Dead-consumer failover | `claim-timeout` | 60 s | No — reclaim of a crashed owner only |
| Lease heartbeat | derived `= claim-timeout/3` | 20 s | No — internal |
| Single-invocation safety bound | `max-processing-time` | 15 m | bounds one `accept()` call |

**One cadence knob (`pollInterval`) drives both new-message polling and re-polling.** A
separate `re-poll-interval` is intentionally **deferred** — see §7.1.

## 3. Goals

1. The listener thread never blocks on a handler; handlers run concurrently on a bounded
   pool (fixes 1.1).
2. An alive consumer keeps its message for as long as its handler runs, regardless of
   duration — no reclaim of live work, no routine duplicate (fixes 1.2).
3. Re-poll cadence of a not-yet-terminal command is `pollInterval`, independent of
   `claim-timeout` (fixes 1.3).
4. `claim-timeout` means **only** dead-consumer failover; a bounded `max-processing-time`
   caps a single hung invocation.
5. `concurrency` (pool size) defaults to **1** (one concurrent handler); the behavior
   deltas below apply uniformly (see §11 rollout).
6. Fix lives in the shared layer so every consumer benefits and `lib-cmd-queue-redis` can
   delete its own pool/lock/timeout machinery.

## 4. Non-goals

- Making duplicate execution *impossible* — remains at-least-once; the lease removes
  *routine* duplication and narrows the window to genuine lease-expiry (crash / >`max`
  hang). Handlers must stay idempotent.
- Ordering guarantees under `concurrency > 1`.
- Changing the consumer-group scheme or the round-robin claim cursor (`:201-256`).
- A distributed lock per command — the message-level lease *is* the exclusion (§5.4).

## 5. Design

### 5.1 SPI evolution — lease-based `MessageStream<M>`

`consume()` fuses read+accept+ack, so the base layer never sees a message handle and
cannot hold one across an async boundary. Add a lease triad:

```java
/** One delivered message plus the token needed to renew/ack/release it. */
record Lease<M>(String id, M message) {}

/** Read one message (new or reclaimed) WITHOUT acking; null if none. */
Lease<M> poll(String streamId);

/** Reset lease idle time (heartbeat). No-op where there is no PEL. */
void renew(String streamId, String leaseId);

/** Acknowledge terminal processing (XACK + XDEL). */
void ack(String streamId, String leaseId);

/** Release without acking so it is redelivered later (nack; used on shutdown). */
void release(String streamId, String leaseId);
```

- **Redis:** `poll` = current `XAUTOCLAIM`-then-`XREADGROUP` read (`:147-153`) minus the
  ack; `renew` = `XCLAIM … 0 <id> JUSTID` (Jedis `xclaimJustId`); `ack` = `XACK`+`XDEL`;
  `release` = no-op (entry stays in PEL, reclaimable after `claim-timeout`).
- **Local:** `poll` = `poll()` off the queue; `renew` = no-op; `ack` = drop; `release` =
  re-offer. No PEL ⇒ no lease, no duplicate concern.
- **Backward compat:** keep `consume(streamId, consumer)` as a `default` implemented over
  the triad (`poll` → `accept` → `ack`/`release`). Additive ⇒ `1.6.0`. The only in-repo
  implementors are Redis + Local.

### 5.2 Dispatcher + pool + scheduler + heartbeat (`AbstractMessageStream`)

```
inFlight : map leaseKey -> Lease          # held from pickup to terminal/crash; heartbeated
active   : map leaseKey -> startMillis     # subset whose accept() is running now
pool     : bounded worker pool (size = concurrency())
scheduler: ScheduledExecutorService        # delayed re-poll re-submissions
heartbeat: ScheduledExecutorService, every claim-timeout/3

dispatcher thread (was the listener thread):
  for each stream with free pool capacity:
      lease = stream.poll(streamId)          # DO NOT ack
      if lease != null:
          inFlight.put(lease); pool.submit(() -> run(lease))
  if nothing polled this cycle: sleep(pollInterval)

run(lease):                                  # pool thread
  active.put(lease, now)
  try:      accepted = consumer.accept(decode(lease.message))
  finally:  active.remove(lease)
  if accepted:                               # terminal
      stream.ack(streamId, lease.id); inFlight.remove(lease)
  else:                                      # not-yet-terminal (RUNNING) — Model B
      scheduler.schedule(() -> pool.submit(() -> run(lease)), pollInterval)
      # lease STAYS in inFlight -> heartbeat keeps renewing -> no reclaim, no migration;
      # the next accept() is scheduled AFTER this one returned -> strictly serial

heartbeat daemon: for each lease in inFlight:
  if now - active.getOrDefault(lease, now) > maxProcessingTime:   # hung single invocation
      inFlight.remove(lease)                 # stop renewing -> reclaimable after claim-timeout
  else:
      stream.renew(streamId, lease.id)
```

Key properties:

- **Non-blocking:** the dispatcher never awaits a handler (fixes 1.1).
- **Serial per command:** the next `accept()` for a lease is scheduled only *after* the
  previous returned — never two invocations of the same command concurrently on one
  replica.
- **Single owner across replicas:** a held, heartbeated entry has idle ≈ 0, so no peer's
  `XAUTOCLAIM` (min-idle = `claim-timeout`) can reclaim it, and this replica's own `poll`
  won't re-return it. Only crash (heartbeat stops) hands it off.
- **Re-poll is in-process:** after first pickup, re-polls touch Redis only for heartbeat
  renewals + the final ack. The `claim-timeout` re-delivery gate is out of the re-poll
  path (fixes 1.3).
- **Backpressure:** the dispatcher polls a stream only when a pool slot is free; unread
  messages stay in the stream. **Reserve capacity for re-polls** so in-flight commands
  are not starved by new intake (see §6).

### 5.3 Max-processing-time (safety valve)

Bounds a **single `accept()` invocation**, not the total lease lifetime (a command that
legitimately polls for hours is fine). Measured via the `active` map; past
`max-processing-time` the daemon stops renewing that lease so it becomes reclaimable. It
does **not** interrupt the handler thread. Default `15m` (sched's worst single call ~5 m).

### 5.4 The per-command lock is unnecessary

The renewed PEL entry is a per-message, single-owner, liveness-based lease across
replicas. For consumers where one unit of work == one message (`lib-cmd-queue-redis`:
one command == one message) this *is* command-level exclusion — no separate distributed
lock. (A consumer needing exclusion coarser than one message is out of scope.)

## 6. Concurrency & thread-safety

- `inFlight` / `active` are `ConcurrentHashMap`s keyed by `streamId|leaseId`. The daemon
  iterates a snapshot and tolerates concurrent removal.
- The heartbeat daemon uses its **own** Jedis resource.
- A transient Redis error in a renewal cycle is logged and swallowed; the next tick
  retries (interval = `claim-timeout/3` tolerates two misses).
- **Pool starvation:** a scheduled re-poll needs a slot; if new intake fills the pool,
  held commands stall. Reserve a slice of capacity for re-polls (or use a separate small
  re-poll executor) so already-owned commands always make progress.
- **Shutdown (`close()`):** stop the dispatcher; cancel pending scheduled re-polls; drain
  the pool (bounded await, then `shutdownNow`) so active handlers finish and ack; stop the
  heartbeat daemon last (remaining leases lapse → peers reclaim).

## 7. Config (`RedisStreamConfig`, additive defaults)

```java
/** How often in-flight leases are renewed. Must be < claim-timeout. */
default Duration getHeartbeatInterval() { return getClaimTimeout().dividedBy(3); }
default long getHeartbeatIntervalMillis() { return getHeartbeatInterval().toMillis(); }

/** Upper bound on a single accept() run before its lease is released (safety valve). */
default Duration getMaxProcessingTime() { return Duration.ofMinutes(15); }
default long getMaxProcessingTimeMillis() { return getMaxProcessingTime().toMillis(); }
```

`concurrency` (pool size) is a base-layer concern on `AbstractMessageStream`
(`protected int concurrency() { return 1; }`), overridable by subclasses.

### 7.1 Deferred: separate `re-poll-interval`

Re-poll reuses `pollInterval` (1 s), which fixes #303 immediately. A distinct
`re-poll-interval` is **deferred** until a real trigger appears: at scale, re-polling
every RUNNING command every 1 s (e.g. 1000 ECS tasks → ~1000 `describeTask`/s) will hit
backend API rate limits; that is when to split status-poll cadence from `pollInterval`.
It is a small additive change; do not pre-build it.

## 8. Downstream — `lib-cmd-queue-redis`

- Delete `executeWithTimeout` / the `BLOCKING` executor / `future.get(timeout)`. Both
  `execute()` and `checkStatus()` run on the shared pool worker; neither blocks the loop.
- No per-command lock. Cross-replica single-runner comes from the lease (§5.4).
- `processCommand` stays thin: load state → terminal? ack → no handler? `FAILED`+ack →
  run `execute`/`checkStatus` → terminal ⇒ `true` (ack); `running()` ⇒ `false`
  (re-polled after `pollInterval`).
- `CommandQueue` sets `concurrency() > 1` to enable parallelism.

## 9. Failure modes

| Failure | Behavior |
|---|---|
| Handler throws | `finally` clears `active`; maps to `false`/terminal `FAILED`. No leak. |
| Worker crashes (JVM alive) | dispatcher's `run` wrapper removes the lease on any exit path; otherwise `max-processing-time` releases it. |
| Pod dies | heartbeat stops with JVM → reclaim after `claim-timeout`; re-poll resumes on the new owner. |
| Redis blip during renew | swallowed; next tick retries. Outage > `claim-timeout` → possible reclaim/duplicate (needs idempotency). |
| Pool saturated | dispatcher stops polling new work; re-polls keep reserved capacity (§6). |
| Cancel | store set terminal; next re-poll (≤ `pollInterval`) sees terminal → ack. |

## 10. Test plan (Testcontainers + unit)

1. Non-blocking: slow handler on stream A does not delay a fast handler on stream B.
2. Concurrency: N messages complete in ~max(handler) not ~sum.
3. No reclaim of live work: handler running > `claim-timeout` never delivered twice
   (single- and two-instance).
4. Failover: kill the owning instance mid-handler → peer reclaims after `claim-timeout`.
5. Re-poll cadence decoupled (#303): a `RUNNING` command is re-invoked at ~`pollInterval`
   regardless of `claim-timeout`; a slow handler does not perturb other commands' cadence.
6. Serial per command: never two concurrent `accept()` for one lease.
7. `max-processing-time`: a single invocation exceeding the bound releases its lease.
8. Backpressure: with pool size K and > K ready messages, at most K run concurrently.
9. `concurrency() == 1` default: at most one concurrent handler.
10. Local backend: concurrency works; `renew` no-op; `release` re-offers.
11. Backward compat: the `default consume()` path still acks-on-true / releases-on-false.

## 11. Rollout

1. Ship `1.6.0` with the SPI triad + pool + heartbeat + Model B, `concurrency` default 1.
2. `lib-cmd-queue-redis`: adopt the lease model, delete `executeWithTimeout`/lock, set
   `concurrency > 1`; validate against sched's scale-up (#600) and status-poll (#303) paths.
3. Regression-test `wave` before raising its `concurrency`.

Behavior deltas to note for every consumer (even at `concurrency == 1`): ack is deferred
to handler completion; re-poll of a not-yet-terminal message is `pollInterval` (was
`claim-timeout`); a long handler no longer blocks other streams.

## 12. Rejected alternatives

- **Fix only in `lib-cmd-queue-redis`.** Leaves 1.1 for every other consumer; duplicates
  machinery.
- **Per-command distributed lock.** Redundant given the PEL lease (§5.4); extra key/command.
- **Short `claim-timeout` (Model A) for #303.** Fixes #303 but couples re-poll to failover:
  ~12× heartbeat traffic at 5 s vs 60 s, narrow pause-tolerance, and re-elevated
  provisioning-duplicate risk. Model B keeps `claim-timeout` long.
- **Accept()-scoped heartbeat (libseqera#73 as written).** Correct only while `accept()`
  blocks the listener thread; incompatible with async dispatch.
