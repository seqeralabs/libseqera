# lib-data-workqueue

A distributed, reliable **work queue**: competing consumers, one live owner per entry,
acknowledgment, a lease with heartbeat renewal, redelivery and dead-owner reclaim — plus an
in-memory implementation for local and test use. The Redis backend lives in the companion
module [`lib-data-workqueue-redis`](../lib-data-workqueue-redis/README.md).

> **Supersedes `lib-data-stream-redis`.** This module is the reliable work queue that
> [#86](https://github.com/seqeralabs/libseqera/pull/86) renamed into place and
> [#100](https://github.com/seqeralabs/libseqera/pull/100) then left unpublished as a
> placeholder, "because [it carries] the lease-based design forward". 2.0.0 fills that
> placeholder with the implementation the scheduler has been running: a
> `MessageConsumer.Decision` returned from `consume()`, a `MessageLease` settlement handle,
> a cooperative drain, and heartbeat renewal pushed down into the Redis backend.
>
> **2.0.0 is a breaking SPI change over the unpublished 1.0.0**, which exposed
> `receive`/`renewLease`/`ack`/`release` around a `Lease<M>` *record* and drove handlers from
> an executor and semaphore owned by `AbstractWorkQueue`. This version exposes
> `init`/`offer`/`consume`/`length`, returns a `Decision` from `consume()`, and hands the
> consumer a `MessageLease` *interface*. There is no migration path between the two; nothing
> in production consumed 1.0.0.
>
> The published `io.seqera:lib-data-stream-redis` artifact still exists and is still used by
> other services on older pinned versions; those are unaffected by anything here.

## Installation

```gradle
dependencies {
    implementation 'io.seqera:lib-data-workqueue:2.0.0'
    // for the Redis-backed implementation also add:
    // implementation 'io.seqera:lib-data-workqueue-redis:2.0.0'
}
```

The library is pure Java: no Groovy runtime dependency, and no jedis — Redis lives entirely in
`lib-data-workqueue-redis`.

## Cooperative shutdown

`AbstractWorkQueue` never interrupts its dispatcher thread: an interrupt landing in a Redis
read can hand a RESP-desynced connection back to the pool (libseqera#92), and it was observed
propagating into a consumer still doing useful work. Shutdown is flag-based instead:

- **`awaitQuiescent(timeout)`** — stop claiming new messages and wait for the dispatcher to finish
  the message it holds, *without* releasing the queue. Call this first when consumers need
  collaborators (a datasource, for instance) that are about to be torn down; returns `false` if the
  dispatcher is still running at the deadline, and the caller decides what that means.
- **`close(timeout)`** — cooperative stop bounded by the *caller's* remaining budget, for callers
  that already spent part of an overall shutdown budget on a drain. Never interrupts: the `closing`
  flag guarantees the dispatcher exits at its next loop-head check, and the thread is a daemon.
- **`close()`** — same, with the `closeTimeout()` default (10s). **Only the first close waits**;
  repeated calls — an explicit drain followed by a `@PreDestroy` backstop — return immediately, so
  a shutdown budget is never spent twice.

## Message leases

`MessageConsumer.accept(message, lease)` returns a `Decision` instead of a boolean:

- **`ACK`** — settle now: the entry is acknowledged and removed from the queue.
- **`RETRY`** — leave pending: the entry redelivers after the visibility timeout. A thrown
  exception settles the same way (and propagates to the caller).
- **`DEFERRED`** — a task now owns the entry via the `MessageLease` handle and settles it
  later with `lease.ack()` or `lease.retry()`, from any thread. Settlement is idempotent —
  first call wins.

On the Redis implementation a delivered entry is *leased*: a single background scheduler
renews every in-flight entry per queue in one round-trip (`XPENDING` ownership check +
one variadic `XCLAIM JUSTID`) at `visibility-timeout / 4`, so a live consumer can run past
the visibility timeout without the entry being stolen — the visibility timeout detects dead
consumers only. Leases stolen during a renewal outage are dropped (never re-seized) and
counted; leases older than 3× the visibility timeout whose owner is not provably alive are
treated as registry leaks and released to the claim cycle — `lease.bindLiveness(probe)` binds
the owning task's liveness (typically `() -> !task.isDone()`), and a lease whose probe reports
the owner alive is never age-pruned, however long it runs. The local implementation
mirrors the settlement semantics in-memory, with no visibility clock: a `RETRY` is
redelivered after a short delay (`workqueue.local.retry-delay`, default 1s).

`MessageConsumer.ready()` (default `true`) is an admission gate: the dispatcher does not
claim from a queue while its consumer reports not ready; skipped polls count as
`saturated` in the metrics.

## Metrics (optional)

`AbstractWorkQueue` can publish [Micrometer](https://micrometer.io/) metrics when a
`QueueMetrics` handle is supplied to the constructor. Micrometer is a `compileOnly`
dependency: consumers that don't opt in have no runtime requirement on `micrometer-core`.

```groovy
import io.seqera.data.workqueue.metrics.MicrometerQueueMetrics

class MyQueue extends AbstractWorkQueue<MyEvent> {
    @Inject
    MyQueue(WorkQueue<String> target, @Nullable MeterRegistry registry) {
        super(target, registry != null
                ? new MicrometerQueueMetrics(registry, 'my-queue')
                : null)
    }
    // ...
}
```

The `QueueMetrics` interface is the neutral seam; `AbstractWorkQueue` itself never
references `MeterRegistry`, so subclasses that don't want metrics (using the 1-arg
constructor) can be loaded and instantiated even when `micrometer-core` is absent from
the classpath.

The lease meters (`seqera.workqueue.leased`, `seqera.workqueue.lease.*`) are recorded one layer
below, by `RedisWorkQueue`, which injects an *optional* `QueueMetrics` bean from the
DI context and falls back to a no-op when none exists. Deployments that want the lease
metrics must therefore provide a `QueueMetrics` bean, typically from the same factory that
builds the queue and guarded on a `MeterRegistry` being present.

When enabled, the following meters are published. All meters carry the base tags
`queue` (the subclass `name()`, e.g. `cmd-queue`) and `queue_id` (the actual Redis
stream key, e.g. `cmd-queue/v1`).

| Meter | Type | Additional tags | Unit | Description |
|---|---|---|---|---|
| `seqera.workqueue.entries` | Gauge | — | entries | Current queue backlog (Redis `XLEN`, polled at scrape time). |
| `seqera.workqueue.messages` | Counter | `outcome` | messages | Total messages processed per outcome. |
| `seqera.workqueue.processing` | Timer | `outcome` | seconds | Per-entry processing time. Includes the full lifecycle from the underlying `queue.consume(...)` entry through the consumer's `accept` and the Redis acknowledge/delete. Published as a Prometheus histogram (with buckets) so quantiles can be aggregated server-side across replicas via `histogram_quantile()`. |
| `seqera.workqueue.deferred` | Counter | — | — | Deliveries whose consumer returned `DEFERRED` (also counted as `active` on `seqera.workqueue.messages`). |
| `seqera.workqueue.saturated` | Counter | — | — | Polls skipped because the consumer's `ready()` admission gate was closed. |
| `seqera.workqueue.leased` | Gauge | — | entries | Entries currently leased (in-flight), sampled at each renewal tick. Tagged `queue` only. |
| `seqera.workqueue.lease.age.max` | Gauge | — | seconds | Age of the oldest currently-leased entry, sampled at each renewal tick. Tagged `queue` only. |
| `seqera.workqueue.lease.renewal` | Timer | — | seconds | Duration of one lease-renewal tick. Tagged `queue` only. |
| `seqera.workqueue.lease.renewal.errors` | Counter | — | — | Failed renewal round-trips (retried on the next tick). Tagged `queue` only. |
| `seqera.workqueue.lease.renewal.age` | Gauge | — | seconds | Seconds since the last *completed* renewal tick — the stuck-tick detector. Tagged `queue` only. |
| `seqera.workqueue.lease.lost` | Counter | — | — | Leases found owned by another consumer at renewal — the observable residual duplicate window. Tagged `queue` only. |
| `seqera.workqueue.lease.leak` | Counter | — | — | Leases dropped by the age backstop: older than 3× the visibility timeout with no provably-alive owner. Tagged `queue` only. |

The `outcome` tag takes one of three values:

- `processed` — the consumer decided `ACK`; the message was acknowledged and removed from the queue.
- `active` — the consumer decided `RETRY` or `DEFERRED`; the message remains pending (work still in progress, not a failure).
- `errored` — an unhandled exception escaped the consumer or the underlying queue implementation.

Empty polls (no message available) are **ignored** — they do not increment
`seqera.workqueue.messages_total` and do not contribute to the timer, keeping the timer's
`_count`/`_sum`/`_max` aligned with "an entry was processed".

In a Prometheus scrape (`micronaut-micrometer-registry-prometheus`), dots in meter names
are translated to underscores. A typical scrape output looks like:

```bash
$ curl -s http://localhost:7070/prometheus | grep '^seqera_workqueue'
seqera_workqueue_entries{queue="cmd-queue",queue_id="cmd-queue/v1"} 0.0
seqera_workqueue_messages_total{outcome="processed",queue="cmd-queue",queue_id="cmd-queue/v1"} 3.0
seqera_workqueue_messages_total{outcome="active",queue="cmd-queue",queue_id="cmd-queue/v1"} 17.0
seqera_workqueue_processing_seconds_count{outcome="processed",queue="cmd-queue",queue_id="cmd-queue/v1"} 3
seqera_workqueue_processing_seconds_sum{outcome="processed",queue="cmd-queue",queue_id="cmd-queue/v1"} 0.158618375
seqera_workqueue_processing_seconds_max{outcome="processed",queue="cmd-queue",queue_id="cmd-queue/v1"} 0.120260875
seqera_workqueue_processing_seconds_bucket{outcome="processed",queue="cmd-queue",queue_id="cmd-queue/v1",le="0.001048576"} 0
# … and the rest of the histogram buckets, with le=… up to +Inf
```

### Useful PromQL queries

```promql
# throughput (messages/sec, by queue)
rate(seqera_workqueue_messages_total{outcome="processed"}[1m])

# error rate (messages/sec)
rate(seqera_workqueue_messages_total{outcome="errored"}[1m])

# error ratio
  sum by (queue) (rate(seqera_workqueue_messages_total{outcome="errored"}[5m]))
/ sum by (queue) (rate(seqera_workqueue_messages_total[5m]))

# active-redelivery rate (in-progress polls, not failures)
rate(seqera_workqueue_messages_total{outcome="active"}[1m])

# percentile latencies (server-side aggregation across replicas)
histogram_quantile(0.25, sum by (le, queue) (rate(seqera_workqueue_processing_seconds_bucket{outcome="processed"}[5m])))  # q1
histogram_quantile(0.50, sum by (le, queue) (rate(seqera_workqueue_processing_seconds_bucket{outcome="processed"}[5m])))  # median
histogram_quantile(0.75, sum by (le, queue) (rate(seqera_workqueue_processing_seconds_bucket{outcome="processed"}[5m])))  # q3
histogram_quantile(0.95, sum by (le, queue) (rate(seqera_workqueue_processing_seconds_bucket{outcome="processed"}[5m])))  # p95

# mean latency
  rate(seqera_workqueue_processing_seconds_sum{outcome="processed"}[5m])
/ rate(seqera_workqueue_processing_seconds_count{outcome="processed"}[5m])

# max latency (rolling, exposed directly)
seqera_workqueue_processing_seconds_max{outcome="processed"}

# current backlog
seqera_workqueue_entries
```

To segregate metrics by application in multi-service deployments, set a common tag at the
`MeterRegistry` boundary (e.g. `micronaut.metrics.tags.application: <name>` in Micronaut).
Every metric in the JVM — including these — will then carry an `application` tag.

## Usage

Work distribution with consumer groups and message acknowledgment:

```groovy
@Inject
WorkQueue<ActivityEvent> workQueue

// Initialize queue
workQueue.init("user-activity")

// Publish events
def event = new ActivityEvent(
    userId: "user123",
    action: "login",
    timestamp: Instant.now()
)
workQueue.offer("user-activity", event)

// Consume events
class ActivityConsumer implements MessageConsumer<ActivityEvent> {
    @Override
    MessageConsumer.Decision accept(ActivityEvent event, MessageLease lease) {
        analyticsService.recordActivity(event)
        return MessageConsumer.Decision.ACK // Acknowledge message
    }
}

workQueue.consume("user-activity", new ActivityConsumer())
```

## Testing

```bash
./gradlew :lib-data-workqueue:test
```
