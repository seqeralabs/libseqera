# lib-data-stream-redis

> **⚠️ Deprecated.** This module is frozen and kept only for existing consumers; no further
> changes will be made here. You have two paths:
>
> - **Move to [`lib-data-workqueue`](../lib-data-workqueue/README.md) +
>   [`lib-data-workqueue-redis`](../lib-data-workqueue-redis/README.md)** — the split/rename of
>   this library with aligned vocabulary (`poll`→`receive`, `renew`→`renewLease`,
>   `claim-timeout`→`visibility-timeout`). `workqueue 1.0.0` ≡ this library's `2.0.0` behaviour
>   (async, at-least-once, heartbeat lease — handlers must be idempotent). Recommended for new
>   code. See the [migration guide](../docs/superpowers/specs/2026-07-11-workqueue-rename-migration.md).
> - **Stay on `lib-data-stream-redis:1.5.x`** — the last *synchronous* release (handler runs on
>   the listener thread, exactly-once-per-poll). Pin `1.5.x` if you don't want the `2.0.0`
>   async/at-least-once rewrite and aren't ready to adopt the idempotency requirement.

Message streaming with Redis Streams and local implementations for persistent event processing.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-stream-redis:2.0.0'
}
```

As of version 1.3.0, this library no longer requires Groovy as a runtime dependency.

## Metrics (optional)

`AbstractMessageStream` can publish [Micrometer](https://micrometer.io/) metrics when a
`StreamMetrics` handle is supplied to the constructor. Micrometer is a `compileOnly`
dependency: consumers that don't opt in have no runtime requirement on `micrometer-core`.

```groovy
import io.seqera.data.stream.metrics.MicrometerStreamMetrics

class MyStream extends AbstractMessageStream<MyEvent> {
    @Inject
    MyStream(MessageStream<String> target, @Nullable MeterRegistry registry) {
        super(target, registry != null
                ? new MicrometerStreamMetrics(registry, 'my-stream')
                : null)
    }
    // ...
}
```

The `StreamMetrics` interface is the neutral seam; `AbstractMessageStream` itself never
references `MeterRegistry`, so subclasses that don't want metrics (using the 1-arg
constructor) can be loaded and instantiated even when `micrometer-core` is absent from
the classpath.

When enabled, the following meters are published. All meters carry the base tags
`stream` (the subclass `name()`, e.g. `cmd-queue`) and `stream_id` (the actual Redis
stream key, e.g. `cmd-queue/v1`).

| Meter | Type | Additional tags | Unit | Description |
|---|---|---|---|---|
| `seqera.stream.entries` | Gauge | — | entries | Current stream backlog (Redis `XLEN`, polled at scrape time). |
| `seqera.stream.messages` | Counter | `outcome` | messages | Total messages processed per outcome. |
| `seqera.stream.processing` | Timer | `outcome` | seconds | Per-entry processing time. Includes the full lifecycle from the underlying `stream.consume(...)` entry through the consumer's `accept` and the Redis acknowledge/delete. Published as a Prometheus histogram (with buckets) so quantiles can be aggregated server-side across replicas via `histogram_quantile()`. |

The `outcome` tag takes one of three values:

- `processed` — the consumer returned `true`; the message was acknowledged and removed from the stream.
- `active` — the consumer returned `false`; the message remains available for redelivery (work still in progress, not a failure).
- `errored` — an unhandled exception escaped the consumer or the underlying stream implementation.

Empty polls (no message available) are **ignored** — they do not increment
`seqera.stream.messages_total` and do not contribute to the timer, keeping the timer's
`_count`/`_sum`/`_max` aligned with "an entry was processed".

In a Prometheus scrape (`micronaut-micrometer-registry-prometheus`), dots in meter names
are translated to underscores. A typical scrape output looks like:

```bash
$ curl -s http://localhost:7070/prometheus | grep '^seqera_stream'
seqera_stream_entries{stream="cmd-queue",stream_id="cmd-queue/v1"} 0.0
seqera_stream_messages_total{outcome="processed",stream="cmd-queue",stream_id="cmd-queue/v1"} 3.0
seqera_stream_messages_total{outcome="active",stream="cmd-queue",stream_id="cmd-queue/v1"} 17.0
seqera_stream_processing_seconds_count{outcome="processed",stream="cmd-queue",stream_id="cmd-queue/v1"} 3
seqera_stream_processing_seconds_sum{outcome="processed",stream="cmd-queue",stream_id="cmd-queue/v1"} 0.158618375
seqera_stream_processing_seconds_max{outcome="processed",stream="cmd-queue",stream_id="cmd-queue/v1"} 0.120260875
seqera_stream_processing_seconds_bucket{outcome="processed",stream="cmd-queue",stream_id="cmd-queue/v1",le="0.001048576"} 0
# … and the rest of the histogram buckets, with le=… up to +Inf
```

### Useful PromQL queries

```promql
# throughput (messages/sec, by stream)
rate(seqera_stream_messages_total{outcome="processed"}[1m])

# error rate (messages/sec)
rate(seqera_stream_messages_total{outcome="errored"}[1m])

# error ratio
  sum by (stream) (rate(seqera_stream_messages_total{outcome="errored"}[5m]))
/ sum by (stream) (rate(seqera_stream_messages_total[5m]))

# active-redelivery rate (in-progress polls, not failures)
rate(seqera_stream_messages_total{outcome="active"}[1m])

# percentile latencies (server-side aggregation across replicas)
histogram_quantile(0.25, sum by (le, stream) (rate(seqera_stream_processing_seconds_bucket{outcome="processed"}[5m])))  # q1
histogram_quantile(0.50, sum by (le, stream) (rate(seqera_stream_processing_seconds_bucket{outcome="processed"}[5m])))  # median
histogram_quantile(0.75, sum by (le, stream) (rate(seqera_stream_processing_seconds_bucket{outcome="processed"}[5m])))  # q3
histogram_quantile(0.95, sum by (le, stream) (rate(seqera_stream_processing_seconds_bucket{outcome="processed"}[5m])))  # p95

# mean latency
  rate(seqera_stream_processing_seconds_sum{outcome="processed"}[5m])
/ rate(seqera_stream_processing_seconds_count{outcome="processed"}[5m])

# max latency (rolling, exposed directly)
seqera_stream_processing_seconds_max{outcome="processed"}

# current backlog
seqera_stream_entries
```

To segregate metrics by application in multi-service deployments, set a common tag at the
`MeterRegistry` boundary (e.g. `micronaut.metrics.tags.application: <name>` in Micronaut).
Every metric in the JVM — including these — will then carry an `application` tag.

## Usage

Event streaming with consumer groups and message acknowledgment:

```groovy
@Inject
MessageStream<ActivityEvent> messageStream

// Initialize stream
messageStream.init("user-activity")

// Publish events
def event = new ActivityEvent(
    userId: "user123",
    action: "login",
    timestamp: Instant.now()
)
messageStream.offer("user-activity", event)

// Consume events
class ActivityConsumer implements MessageConsumer<ActivityEvent> {
    @Override
    boolean consume(ActivityEvent event) {
        analyticsService.recordActivity(event)
        return true // Acknowledge message
    }
}

messageStream.consume("user-activity", new ActivityConsumer())
```

## Architecture

`AbstractMessageStream` runs handlers **asynchronously and concurrently** while
guaranteeing that a given message is processed by exactly one *live* consumer at a
time. A message is owned by its consumer for as long as the handler keeps working —
independent of how long that takes — and ownership is relinquished only when the work
finishes or the consumer dies.

```
  offer(msg)                                    ┌────────────────────────────────┐
      │                                         │      AbstractMessageStream     │
      ▼                                         │                                │
 ┌──────────┐   poll (XREADGROUP / XAUTOCLAIM)  │  dispatcher thread             │
 │  Redis   │◀──────────────────────────────────┤   • acquire a semaphore slot   │
 │  stream  │                                   │   • poll one message           │
 │  (PEL,   │   renew (XCLAIM … JUSTID)         │   • hand it to the executor    │
 │  group)  │◀───────────── heartbeat daemon ───┤     (never runs it inline)     │
 │          │        every claim-timeout/3      │                                │
 │          │   ack (XACK + XDEL)               │  worker (virtual thread)       │
 │          │◀──────────── on terminal ─────────┤   accept(msg):                 │
 └──────────┘                                   │    ├─ true  → ack + free slot  │
      ▲                                         │    └─ false → keep lease,      │
      │ reclaimed by a peer only if the owner   │       re-run after pollInterval│
      │ dies (heartbeat stops → idle > claim-timeout)  via the re-poll scheduler │
      └──────────────────────────────────────────────────────────────────────────┘
```

**Three mechanisms:**

1. **Async dispatch (no head-of-line blocking).** The dispatcher thread never runs a
   handler; it hands each message to a worker executor and moves on. Handlers run on a
   shared **virtual-thread** executor by default, or the Micronaut `@Named(BLOCKING)`
   executor when supplied via `withHandlerExecutor(...)`. A `Semaphore` sized by
   `concurrency()` bounds how many messages are in flight at once (backpressure: excess
   messages stay in the stream).

2. **Heartbeat lease (single live runner + safe long handlers).** While a message is in
   flight, a daemon renews its Redis consumer-group entry (`XCLAIM … JUSTID`) every
   `claim-timeout / 3`, pinning its idle time near zero so no peer's `XAUTOCLAIM` can
   reclaim it — no matter how long the handler runs. If the owning process dies, the
   heartbeat stops, idle time crosses `claim-timeout`, and a peer reclaims the message
   (real dead-consumer failover). A `max-processing-time` safety valve stops renewing a
   single invocation that runs pathologically long, without interrupting its thread.

3. **In-process re-poll for not-yet-terminal work.** When a handler returns `false` (work
   in progress), the message keeps its lease and the handler is **re-invoked in-process**
   after `pollInterval` via a scheduler — Redis is not re-read. This makes the re-poll
   cadence independent of `claim-timeout` (which then governs only failover).

   The re-poll is scheduled **after the handler returns** — a fixed *delay*, not a fixed
   *rate*. So a handler that runs **longer than `pollInterval` never overlaps itself**: the
   next call starts `pollInterval` after the previous one finished, and a given message is
   processed by at most one invocation at a time (regardless of how slow the handler is).
   The *only* exception is an invocation that exceeds `max-processing-time` — the safety
   valve then stops renewing the lease so a concurrent reclaim becomes possible (see below),
   which is why handlers must be idempotent.

Delivery is **at-least-once** (a crash/pause beyond `claim-timeout`, or the
`max-processing-time` valve, can hand a still-running message to a peer), so consumers
must be idempotent. The in-memory `LocalMessageStream` has no pending-entries list, so it
has no lease/heartbeat (renew is a no-op); it still benefits from async, concurrent dispatch.

### Configuration

| Knob | Where | Default | Governs |
|---|---|---|---|
| `pollInterval()` | `AbstractMessageStream` | — (subclass) | Idle backoff **and** in-process re-poll cadence |
| `concurrency()` | `AbstractMessageStream` | `1` | Max in-flight messages (semaphore ceiling) |
| `getClaimTimeout()` | `RedisStreamConfig` | — | Dead-consumer failover window |
| `getHeartbeatInterval()` | `RedisStreamConfig` | `claim-timeout / 3` | Lease renewal cadence |
| `getMaxProcessingTime()` | `RedisStreamConfig` | `15m` | Upper bound on a single `accept()` before its lease is released |

## Testing

```bash
./gradlew :lib-data-stream-redis:test
```
