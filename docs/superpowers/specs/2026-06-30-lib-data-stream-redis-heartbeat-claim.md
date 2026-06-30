# Design — Heartbeat-based claim leasing for `lib-data-stream-redis`

- **Status:** Proposed
- **Date:** 2026-06-30 (spec)
- **Author:** Paolo Di Tommaso (with Claude Code)
- **Module:** `lib-data-stream-redis` (current `1.5.0` → proposed `1.6.0`)
- **Downstream impact:** `sched`, `wave` (any consumer of `RedisMessageStream`)
- **Motivates:** [seqeralabs/sched#600](https://github.com/seqeralabs/sched/issues/600)

## 1. Problem

`RedisMessageStream` uses Redis consumer groups. New messages are delivered to
exactly one consumer via `XREADGROUP >` (`readMessage`, `RedisMessageStream:175-199`).
A stalled message is recovered by `XAUTOCLAIM` once it has been **idle** (un-acked)
longer than `claim-timeout` (`claimMessage`, `RedisMessageStream:201-255`), and a
message is acknowledged (`XACK` + `XDEL`) **only when the consumer returns `true`**
(`consume`, `RedisMessageStream:155-167`).

`XAUTOCLAIM`'s only liveness signal is idle time — it **cannot distinguish a dead
consumer from a slow-but-alive one**. So `claim-timeout` is silently overloaded with
two unrelated jobs:

1. **Dead-consumer failover window** — how long after a pod dies before its
   in-flight message is recovered.
2. **Re-poll interval for long-running work** — because a not-yet-terminal command
   never acks, its single stream entry sits in the PEL and is only revisited when its
   idle time crosses `claim-timeout`.

These two wants conflict directly: short timeout ⇒ fast failover but **a handler that
runs longer than `claim-timeout` gets its message reclaimed and re-executed while the
original is still running** (duplicate, possibly cross-pod); long timeout ⇒ no false
reclaim but minute-scale failover and minute-scale re-poll.

### 1.1 Concrete failure (sched#600)

Sched's `claim-timeout` is `60s` (`SchedRedisStreamConfig:34`). A VM scale-up handler
(`TaskSubmitHandler.checkStatus → … → launchInstances`) blocked on an Azure ARM
`create()` for ~5 minutes. During that single `consume()` → `accept()` call the
message stayed un-acked; at the 60s mark `XAUTOCLAIM` on another consumer reclaimed it
and ran the **same** handler again — repeatedly — yielding duplicate provisioning and a
churn loop. The duplicate execution is a direct consequence of "idle ≠ dead".

## 2. Goal

Decouple the two concerns so `claim-timeout` means **only** "dead-consumer failover".
A consumer that is alive and actively processing a message must retain ownership for as
long as it keeps working, regardless of how long that takes — without changing the
re-poll cadence of work that is *waiting* between polls.

Add a bounded **max-processing-time** safety valve so a single attempt cannot hold a
message forever (a genuinely hung — not dead — consumer).

## 3. Non-goals

- Changing the `XREADGROUP`/`XAUTOCLAIM`/`XACK`+`XDEL` model, the consumer-group
  scheme, the round-robin claim cursor (`RedisMessageStream:201-255`), or the
  `MessageStream` interface.
- Changing the `CommandService` execute-with-timeout / `checkStatus` polling model in
  `lib-cmd-queue-redis`. This spec is purely about message-ownership leasing in the
  Redis stream layer; it is orthogonal and complementary to any handler-threading work.
- Making duplicate execution *impossible*. Cross-pod idempotency still rests on the
  consumer's own guards (DB optimistic locking, status checks). This spec removes the
  *routine* duplication caused by slow handlers; it does not replace idempotency.
- Touching `LocalMessageStream` — it has no PEL and no claim concept, so there is
  nothing to lease.

## 4. Approach — lease the message while `accept()` runs

The handler invocation in `RedisMessageStream.consume` is a single, well-bounded call:

```java
if (entry != null && consumer.accept(msg = entry.getFields().get(DATA_FIELD))) {
    // XACK + XDEL
}
```

While that `accept(...)` is on the stack, this consumer is provably alive and working
on `entry`. We keep its idle time pinned near zero for that window with a background
**heartbeat** that re-claims the entry to *itself* (`XCLAIM … 0 <id> JUSTID`, which
resets idle time without transferring data). When `accept()` returns — `true`
(terminal, about to be acked) or `false` (RUNNING, waiting for the next poll) — the
heartbeat stops and idle time resumes growing.

This is the crux of the correctness argument:

| Situation | In-flight (heartbeated)? | Outcome |
|---|---|---|
| `accept()` actively running for minutes (e.g. blocking `launchInstances`) | **yes** | idle pinned < `claim-timeout` ⇒ **never reclaimed mid-execution** ⇒ no duplicate |
| `accept()` returned `false` (RUNNING, between polls) | **no** | idle grows ⇒ reclaimed after `claim-timeout` ⇒ next poll. **Re-poll cadence unchanged** |
| pod dies mid-`accept()` | heartbeat thread dies with the JVM | idle grows ⇒ reclaimed after `claim-timeout` ⇒ **real failover preserved** |

The lease is scoped *exactly* to the `accept()` duration via try/finally, so it can
never keep a merely-waiting command owned — that is what preserves the existing polling
behavior for not-yet-terminal commands.

### 4.1 Max-processing-time guard

Each in-flight entry records when `accept()` started. Before refreshing, the heartbeat
checks the elapsed time; if it exceeds `max-processing-time` the heartbeat **stops
refreshing that entry** (and logs a warning + increments a counter). Idle time then
resumes and the entry becomes reclaimable after `claim-timeout`, allowing another
attempt elsewhere.

Trade-off, stated explicitly: if the original `accept()` is still running (truly hung,
not dead) when the guard fires, the reclaim produces a duplicate execution — accepted,
because the consumer is already required to be idempotent for the dead-pod case, and an
unbounded stuck message is worse. `max-processing-time` must therefore be set
comfortably above the longest legitimate single `accept()` call (the blocking
provider call, not the whole command lifecycle — polling commands return between
calls). Default `15m`; sched's worst observed single call is ~5m.

The guard does **not** interrupt the handler thread. `RedisMessageStream` does not own
that thread (it may be the caller's listener thread or a downstream worker), and
forcibly interrupting a partially-completed cloud operation is unsafe. The guard only
governs *message ownership*; killing a runaway handler is the consumer's concern.

## 5. Implementation

All changes are confined to `RedisMessageStream` plus three additive `default` methods
on `RedisStreamConfig`. No interface or call-site changes are required of consumers.

### 5.1 In-flight registry + heartbeat executor (`RedisMessageStream`)

```java
/** Entries this consumer is actively processing, keyed by "streamId|entryId". */
private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

private record InFlight(String streamId, StreamEntryID id, long startedAtMillis) {}

private ScheduledExecutorService heartbeat;

@PostConstruct
private void create() {
    consumerName = "consumer-" + LongRndKey.rndLong();
    log.info("Creating Redis message stream - consumer={}", consumerName);
    heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "redis-stream-heartbeat");
        t.setDaemon(true);
        return t;
    });
    final long periodMs = config.getHeartbeatIntervalMillis();
    heartbeat.scheduleWithFixedDelay(this::renewLeases, periodMs, periodMs, TimeUnit.MILLISECONDS);
}

@PreDestroy
private void destroy() {
    if (heartbeat != null) {
        heartbeat.shutdownNow();
    }
}
```

`consume()` wraps only the `accept()` call (no other line moves):

```java
if (entry != null) {
    final String key = leaseKey(streamId, entry.getID());
    inFlight.put(key, new InFlight(streamId, entry.getID(), System.currentTimeMillis()));
    final boolean accepted;
    try {
        accepted = consumer.accept(msg = entry.getFields().get(DATA_FIELD));
    } finally {
        inFlight.remove(key);   // stop heartbeating the instant accept() returns
    }
    if (accepted) {
        final var tx = jedis.multi();
        tx.xack(streamId, config.getDefaultConsumerGroupName(), entry.getID());
        // … existing warn-timeout log …
        tx.xdel(streamId, entry.getID());
        tx.exec();
        return true;
    }
}
return false;
```

The heartbeat task (own Jedis resource, never the listener's connection):

```java
private void renewLeases() {
    if (inFlight.isEmpty()) return;
    final long now = System.currentTimeMillis();
    final long maxMs = config.getMaxProcessingTimeMillis();
    try (Jedis jedis = pool.getResource()) {
        for (final InFlight f : inFlight.values()) {
            if (now - f.startedAtMillis() > maxMs) {
                // safety valve: stop renewing → entry becomes reclaimable after claim-timeout
                log.warn("Message {} on stream {} exceeded max-processing-time {} — releasing lease",
                        f.id(), f.streamId(), Duration.ofMillis(maxMs));
                inFlight.remove(leaseKey(f.streamId(), f.id()));
                continue;
            }
            // re-claim to self with min-idle 0 → resets idle time, JUSTID avoids data transfer
            jedis.xclaim(f.streamId(), config.getDefaultConsumerGroupName(), consumerName,
                    0L, XClaimParams.xClaimParams(), f.id());
        }
    } catch (Exception e) {
        // never let a transient Redis error kill the scheduler; next tick retries
        log.warn("Lease renewal cycle failed: {}", e.getMessage());
    }
}
```

> Note: use the `JUSTID` form of `XCLAIM` (`xclaimJustId(...)` in Jedis) so only IDs are
> returned; the data is not re-fetched. The exact Jedis method/param overload is an
> implementation detail to confirm against the pinned Jedis version during coding.

### 5.2 Config (`RedisStreamConfig`) — additive defaults

```java
/** How often in-flight messages are re-leased. Must be < claim-timeout. */
default Duration getHeartbeatInterval() {
    return getClaimTimeout().dividedBy(3);
}
default long getHeartbeatIntervalMillis() {
    return getHeartbeatInterval().toMillis();
}

/** Upper bound on a single accept() before its lease is released (safety valve). */
default Duration getMaxProcessingTime() {
    return Duration.ofMinutes(15);
}
default long getMaxProcessingTimeMillis() {
    return getMaxProcessingTime().toMillis();
}
```

Because these are `default` methods, every existing implementation — sched's
`SchedRedisStreamConfig`, wave's config — compiles and runs unchanged and inherits
sensible behavior. Consumers may override (e.g. sched can add
`sched.redis-stream.heartbeat-interval` / `sched.redis-stream.max-processing-time`
`@Value`s) in a follow-up; no such change is required for this library release.

**Invariant:** `heartbeat-interval` must be meaningfully smaller than `claim-timeout`
(default `claim-timeout/3`) so at least two refreshes land inside each claim window,
tolerating one missed tick (GC pause, Redis blip) without a false reclaim.

## 6. Thread-safety

- `inFlight` is a `ConcurrentHashMap`. Writers are listener thread(s) calling
  `consume()`; the single reader is the heartbeat thread. `RedisMessageStream` is a
  shared `@Singleton`, so if multiple `AbstractMessageStream` subclasses share it the
  map legitimately holds entries for several streams at once — the composite
  `streamId|entryId` key keeps them distinct.
- The heartbeat uses its **own** pooled `Jedis` resource; it never touches the
  connection the listener uses inside `consume()`.
- Register-before-`accept`, remove-in-`finally`, then `XACK`+`XDEL`: a heartbeat can
  never fire for an already-removed entry, and the post-`accept` ack window is safe
  because idle time only just began growing (≪ `claim-timeout`).

## 7. Failure modes

| Scenario | Behavior |
|---|---|
| Pod crashes mid-`accept()` | heartbeat dies with JVM → idle grows → reclaimed after `claim-timeout`. Failover preserved. |
| Redis unreachable during a renewal tick | tick logs + returns; next tick retries. If the outage outlasts `claim-timeout`, the entry may be reclaimed — same risk profile as today. |
| Handler hung (alive) past `max-processing-time` | lease released → reclaimed → retried elsewhere (possible duplicate; idempotency covers it). Bounded, not infinite. |
| Handler returns `false` quickly (normal RUNNING poll) | never heartbeated → existing `claim-timeout` re-poll cadence unchanged. |
| `heartbeat-interval ≥ claim-timeout` (misconfig) | a single missed tick can let idle cross the threshold → false reclaim. Mitigated by the default `claim-timeout/3` and a startup `log.warn` if the invariant is violated. |

## 8. Testing

Testcontainers Redis, extending the existing `RedisMessageStreamTest` fixture:

1. **Long handler is not reclaimed.** One consumer; `accept()` blocks > `claim-timeout`
   (set `claim-timeout` low, e.g. `2s`, `heartbeat-interval` ~`700ms`). Assert the
   entry is processed exactly once and `XPENDING` shows continuous single ownership.
2. **Two consumers, slow handler — no duplicate.** Two `RedisMessageStream` instances
   (distinct consumer names), one slow `accept()`. Assert the second never claims the
   in-flight entry; handler runs once.
3. **Re-poll cadence preserved.** Handler returns `false` (RUNNING); assert the entry
   is re-delivered after ~`claim-timeout` (not pinned forever).
4. **Dead consumer failover.** Start an `accept()` that blocks, then stop the heartbeat
   executor (simulating pod death); assert a second consumer reclaims after
   `claim-timeout`.
5. **Max-processing-time guard.** `max-processing-time` < handler duration; assert the
   lease is released, the warn fires, and the entry becomes reclaimable.

## 9. Rollout

1. Implement in `lib-data-stream-redis`; bump `VERSION` to `1.6.0`; update `README.md`
   and `changelog.txt`; `[release] lib-data-stream-redis@1.6.0` commit per repo
   convention.
2. Bump sched's `lib-data-stream-redis` dependency to `1.6.0`. No code change required
   (defaults apply). Optionally expose `heartbeat-interval` / `max-processing-time` as
   `@Value`s in `SchedRedisStreamConfig` and verify in dev against sched#600's scenario.
3. Same optional follow-up for wave if/when needed.

## 10. Alternatives considered (rejected)

- **Raise `claim-timeout` to exceed worst-case handler time.** One-line config, but it
  *also* stretches the RUNNING re-poll cadence (they are the same knob) to minutes and
  slows real dead-pod failover to the same minutes. Treats the symptom, worsens two
  other behaviors. Rejected.
- **Per-consumer liveness registry** (heartbeat a `consumer:<name>:alive` TTL key;
  reclaim only from consumers whose key expired). Same end guarantee as this spec but
  more Redis bookkeeping (`XPENDING` → owner → liveness lookup) for no extra benefit —
  leasing the messages directly is strictly less machinery.
- **ACK-on-receipt + state-driven re-enqueue** (don't keep the entry pending during
  long work; drive lifecycle from `CommandState` + a delayed re-offer). Cleanest
  decoupling, but a substantially larger change: Redis Streams has no native delayed
  delivery, and it introduces an ack-then-crash drop window needing a separate sweeper.
  Out of scope; revisit if sub-`claim-timeout` explicit re-poll cadences are ever
  needed.

## 11. Risk register

| Risk | Mitigation |
|---|---|
| `max-processing-time` set too low cuts off a legitimate long operation. | Generous default (`15m`, ≫ observed 5m); documented as "longest single `accept()`, not whole command lifecycle". |
| Heartbeat thread starves under heavy in-flight load. | Single-threaded, `JUSTID` (ID-only), one pooled connection per tick; in-flight set is small (bounded by listener concurrency). Revisit only if profiling shows contention. |
| Jedis `XCLAIM JUSTID` overload differs across pinned versions. | Confirm the exact signature against the module's Jedis version during implementation; covered by the Testcontainers tests. |
| Consumer relies (today) on the accidental ~`claim-timeout` re-poll of an *actively blocking* handler. | None do — a blocking `accept()` is being reclaimed-and-re-run, which is the bug. The waiting-between-polls cadence (the legitimate use) is explicitly preserved (§4). |
