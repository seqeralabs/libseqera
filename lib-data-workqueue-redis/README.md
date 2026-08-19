# lib-data-workqueue-redis

Redis backend of the work queue: the Redis Streams implementation of the `WorkQueue` SPI
declared by [`lib-data-workqueue`](../lib-data-workqueue/README.md), including the message
lease (a heartbeat on the pending-entries-list entry) that lets a live handler run past the
visibility timeout without its entry being stolen.

> **Supersedes the Redis half of `lib-data-stream-redis`.** This module is the Redis Streams
> backend of [`lib-data-workqueue`](../lib-data-workqueue/README.md), and like its core it
> fills the placeholder that [#86](https://github.com/seqeralabs/libseqera/pull/86) named and
> [#100](https://github.com/seqeralabs/libseqera/pull/100) left unpublished.
>
> **2.0.0 is a breaking change over the unpublished 1.0.0** — it implements the
> `consume()`/`Decision`/`MessageLease` SPI rather than the `receive()`/`Lease<M>` one, and
> owns the lease-renewal daemon that used to live in `AbstractWorkQueue`. Versioned in
> lockstep with `lib-data-workqueue`; the two must be upgraded together.
>
> The published `io.seqera:lib-data-stream-redis` artifact still exists and is still used by
> other services on older pinned versions; those are unaffected by anything here.

## Installation

It exposes
`lib-data-workqueue` as an `api` dependency, since `RedisWorkQueue` publicly implements
`WorkQueue<String>`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-workqueue-redis:2.0.0'
}
```

`RedisWorkQueue` is a `@Singleton` gated on `@Requires(bean = RedisActivator.class)`, so it
activates only when the `redis` environment is active — otherwise `LocalWorkQueue` from the core
module takes its place, with no code change in the consumer.

## Delivery model

One dispatcher poll does, in order:

1. `XAUTOCLAIM` — take over any **stalled** entry whose owner has not renewed within the
   visibility timeout (a dead consumer).
2. `XREADGROUP >` — otherwise read a new entry for this consumer group.
3. Register the entry in the in-flight **lease** registry, keyed per queue (entry IDs are unique
   only per Redis stream key, so a bare `StreamEntryID` key would collide across queues).
4. Run the consumer and settle on its `Decision`: `ACK` → `XACK` + `XDEL`; `RETRY` → drop the
   lease so the idle clock resumes and the entry redelivers; `DEFERRED` → leave the entry leased
   until the consumer's task settles it through `MessageLease`.

A background daemon scheduler renews every in-flight entry **per queue in one round-trip** at
`visibility timeout / 4`: a pipelined per-id `XPENDING` ownership check followed by one variadic
`XCLAIM JUSTID`. An entry taken over during a renewal outage is dropped rather than re-seized
(no ownership ping-pong) and counted on `seqera.workqueue.lease.lost`; a lease older than the max
lease age whose owner is not provably alive — `MessageLease.bindLiveness()` — stops being renewed
so the claim cycle recovers it, counted on `seqera.workqueue.lease.leak`.

The full design, including the renewal margin math and the residual duplicate windows, is in
[`docs/plans/command-execution-guarantee-message-lease.md`](../docs/plans/command-execution-guarantee-message-lease.md).

## Configuration

`RedisWorkQueueConfig` is the SPI the consumer implements — this module binds no property
keys of its own. The consumer chooses the prefix; the settings below, with the values the
Seqera scheduler runs, are the reference for what each one governs:

| Key | Default | Meaning |
|---|---|---|
| `<prefix>.consumer-group` | `sched-workers` | Redis consumer-group name shared by all replicas. |
| `<prefix>.visibility-timeout` | `20s` | Idle time after which an unrenewed entry is claimable by another replica — dead-consumer detection, and the transient-error retry cadence. |
| `<prefix>.consumer-warn-timeout` | `15s` | Warn when a synchronous consume cycle exceeds this. |
| `<prefix>.lease-renewal-period` | derived: `visibility-timeout / 4` | Renewal tick. Startup fails on a period at or above the visibility timeout. |
| `<prefix>.max-lease-age` | derived: `3 × visibility-timeout` | Leak backstop for an unsettled lease with no provably-live owner. |

The Redis connection itself comes from `lib-jedis-pool` (`redis.uri`, `redis.pool.*`). Note
`redis.pool.maxWait`: an unbounded borrow inside a renewal tick would block the single-threaded
scheduler and silently let every lease on the replica go stalled — set it below the renewal
period so a starved tick fails loudly instead.

## Metrics

Recorded through the optional `QueueMetrics` seam of `lib-data-workqueue` — see
[that module's README](../lib-data-workqueue/README.md#metrics-optional) for the meter table.
`RedisWorkQueue` injects `QueueMetrics` as an *optional* bean and falls back to a no-op, so a
deployment that wants the lease meters must provide the bean, typically from the same factory
that builds the queue and guarded on a `MeterRegistry` being present.

## Testing

The tests run against a real Redis in a Testcontainer, so **Docker must be available**:

```bash
./gradlew :lib-data-workqueue-redis:test
```

`RedisWorkQueueLeaseTest` is the suite that would catch an accidental change to the lease
semantics — renewal margin, ownership check, age backstop, settlement idempotence.
