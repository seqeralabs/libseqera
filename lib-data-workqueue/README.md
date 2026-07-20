# lib-data-workqueue

A distributed, reliable **work queue** abstraction with competing consumers, acknowledgment
and lease/visibility-timeout semantics — plus an in-memory implementation for local and test
use. The Redis implementation lives in the companion module
[`lib-data-workqueue-redis`](../lib-data-workqueue-redis).

> **Migrating from `lib-data-stream-redis`?** This module (together with
> `lib-data-workqueue-redis`) is the split/rename of `lib-data-stream-redis` 1.6.0. It keeps
> the exact behaviour and only renames the abstraction to match its real semantics. See the
> full guide at
> [`docs/superpowers/specs/2026-07-11-workqueue-rename-migration.md`](../docs/superpowers/specs/2026-07-11-workqueue-rename-migration.md).

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-workqueue:1.0.0'
    // for the Redis-backed implementation also add:
    // implementation 'io.seqera:lib-data-workqueue-redis:1.0.0'
}
```

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

When enabled, the following meters are published. All meters carry the base tags
`queue` (the subclass `name()`, e.g. `cmd-queue`) and `queue_id` (the actual Redis
stream key, e.g. `cmd-queue/v1`).

| Meter | Type | Additional tags | Unit | Description |
|---|---|---|---|---|
| `seqera.workqueue.entries` | Gauge | — | entries | Current queue backlog (Redis `XLEN`, polled at scrape time). |
| `seqera.workqueue.messages` | Counter | `outcome` | messages | Total messages processed per outcome. |
| `seqera.workqueue.processing` | Timer | `outcome` | seconds | Per-entry processing time. Includes the full lifecycle from the underlying `queue.consume(...)` entry through the consumer's `accept` and the Redis acknowledge/delete. Published as a Prometheus histogram (with buckets) so quantiles can be aggregated server-side across replicas via `histogram_quantile()`. |

The `outcome` tag takes one of three values:

- `processed` — the consumer returned `true`; the message was acknowledged and removed from the queue.
- `active` — the consumer returned `false`; the message remains available for redelivery (work still in progress, not a failure).
- `errored` — an unhandled exception escaped the consumer or the underlying queue implementation.

Empty receives (no message available) are **ignored** — they do not increment
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

# active-redelivery rate (in-progress receives, not failures)
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

Work distribution with competing consumers and message acknowledgment:

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
    boolean accept(ActivityEvent event) {
        analyticsService.recordActivity(event)
        return true // Acknowledge message
    }
}

// Register the consumer; the queue dispatches messages to it asynchronously
workQueue.addConsumer("user-activity", new ActivityConsumer())
```

## Architecture

`AbstractWorkQueue` runs handlers **asynchronously and concurrently** while
guaranteeing that a given message is processed by exactly one *live* consumer at a
time. A message is owned by its consumer for as long as the handler keeps working —
independent of how long that takes — and ownership is relinquished only when the work
finishes or the consumer dies.

```
  offer(msg)                                    ┌──────────────────────────────┐
      │                                         │       AbstractWorkQueue       │
      ▼                                         │                               │
 ┌──────────┐   receive (XREADGROUP/XAUTOCLAIM) │  dispatcher thread            │
 │  Redis   │◀─────────────────────────────────┤   • acquire a semaphore slot  │
 │  stream  │                                   │   • receive one message       │
 │  (PEL,   │  renewLease (XCLAIM … JUSTID)      │   • hand it to the executor   │
 │  group)  │◀──────────── heartbeat daemon ────┤     (never runs it inline)    │
 │          │      every visibility-timeout/3    │                               │
 │          │   ack (XACK + XDEL)                │  worker (virtual thread)      │
 │          │◀──────────── on terminal ─────────┤   accept(msg):                │
 └──────────┘                                   │    ├─ true  → ack + free slot │
      ▲                                         │    └─ false → keep lease,     │
      │ reclaimed by a peer only if the owner   │       re-run after pollInterval│
      │ dies (heartbeat stops → idle > visibility-timeout) via the re-poll sched │
      └─────────────────────────────────────────────────────────────────────────┘
```

**Three mechanisms:**

1. **Async dispatch (no head-of-line blocking).** The dispatcher thread never runs a
   handler; it hands each message to a worker executor and moves on. Handlers run on a
   shared **virtual-thread** executor by default, or the Micronaut `@Named(BLOCKING)`
   executor when supplied via `withHandlerExecutor(...)`. A `Semaphore` sized by
   `concurrency()` bounds how many messages are in flight at once (backpressure: excess
   messages stay in the queue).

2. **Heartbeat lease (single live runner + safe long handlers).** While a message is in
   flight, a daemon renews its Redis consumer-group entry (`XCLAIM … JUSTID`) every
   `visibility-timeout / 3`, pinning its idle time near zero so no peer's `XAUTOCLAIM` can
   reclaim it — no matter how long the handler runs. If the owning process dies, the
   heartbeat stops, idle time crosses the visibility timeout, and a peer reclaims the message
   (real dead-consumer failover). A `max-processing-time` safety valve stops renewing a
   single invocation that runs pathologically long (logged as *stalled*), without
   interrupting its thread.

3. **In-process re-poll for not-yet-terminal work.** When a handler returns `false` (work
   in progress), the message keeps its lease and the handler is **re-invoked in-process**
   after `pollInterval` via a scheduler — Redis is not re-read. This makes the re-poll
   cadence independent of `visibility-timeout` (which then governs only failover). The next
   invocation is scheduled only after the previous one returns, so a given message is
   never processed by two overlapping invocations.

Delivery is **at-least-once** (a crash/pause beyond `visibility-timeout`, or the
`max-processing-time` valve, can hand a still-running message to a peer), so consumers
must be idempotent. The in-memory `LocalWorkQueue` has no pending-entries list, so it
has no lease/heartbeat (renewLease is a no-op); it still benefits from async, concurrent dispatch.

### Configuration

| Knob | Where | Default | Governs |
|---|---|---|---|
| `pollInterval()` | `AbstractWorkQueue` | — (subclass) | Idle backoff **and** in-process re-poll cadence |
| `concurrency()` | `AbstractWorkQueue` | `1` | Max in-flight messages (semaphore ceiling) |
| `getVisibilityTimeout()` | `RedisWorkQueueConfig` | — | Dead-consumer failover window |
| `getHeartbeatInterval()` | `RedisWorkQueueConfig` | `visibility-timeout / 3` | Lease renewal cadence |
| `getMaxProcessingTime()` | `RedisWorkQueueConfig` | `15m` | Upper bound on a single `accept()` before its lease is released |

## Testing

```bash
./gradlew :lib-data-workqueue:test
```
