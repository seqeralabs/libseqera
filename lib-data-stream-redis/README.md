# lib-data-stream-redis

Message streaming with Redis Streams and an in-memory fallback for persistent
event processing.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-stream-redis:1.6.0'
}
```

As of version 1.3.0, this library no longer requires Groovy as a runtime
dependency.

## ⚠️ Breaking change

In previous releases `RedisMessageStream` and `LocalMessageStream` were
auto-registered `@Singleton` beans. The class-level Micronaut annotations
have been removed — both are now plain Java classes with public constructors
and can be instantiated directly.

Micronaut-managed wiring is provided by the two `Default*` subclasses
(`DefaultRedisMessageStream`, `DefaultLocalMessageStream`), each annotated
with `@EachBean(RedisStreamConfig.class)` — see
[Automatic wiring](#automatic-wiring-eachbean) below. Applications that
previously injected `MessageStream<String>` unqualified must now declare at
least one `RedisStreamConfig` bean in their context.

## Implementations

| Class | Use | DI status |
|---|---|---|
| `RedisMessageStream` | Redis Streams backend (distributed, durable) | plain class, construct with `new RedisMessageStream(pool, config)` |
| `LocalMessageStream` | In-memory backend (tests, local dev) | plain class, construct with `new LocalMessageStream()` |
| `DefaultRedisMessageStream` | Micronaut-managed wrapper — active when `RedisActivator` is present | `@EachBean(RedisStreamConfig.class)` |
| `DefaultLocalMessageStream` | Micronaut-managed wrapper — active when `RedisActivator` is absent | `@EachBean(RedisStreamConfig.class)` |

## Automatic wiring (`@EachBean`)

`DefaultRedisMessageStream` / `DefaultLocalMessageStream` are annotated with
`@EachBean(RedisStreamConfig.class)`, so **one `MessageStream` bean is
produced per `RedisStreamConfig` bean in the context**, and each produced
stream inherits its config's qualifier. The Redis / in-memory choice is
resolved automatically by `@Requires(bean = RedisActivator.class)` vs
`@Requires(missingBeans = RedisActivator.class)` on the two subclasses.

Single-queue applications declare one `RedisStreamConfig` bean and inject
`MessageStream<String>` unqualified. Multi-queue applications declare
multiple named configs (typically via `@EachProperty`) and inject by name:

```java
@EachProperty("myapp.queues")
public class AppStreamConfig implements RedisStreamConfig {
    private final String name;
    public AppStreamConfig(@Parameter String name) { this.name = name; }
    // ... getters ...
}
```

```yaml
myapp:
  queues:
    lifecycle:
      claim-timeout: 60s
      consumer-group: app-lifecycle
    monitor:
      claim-timeout: 5s
      consumer-group: app-monitor
```

Micronaut then produces `@Named("lifecycle")` and `@Named("monitor")`
instances of both `RedisStreamConfig` and `MessageStream<String>`. No
hand-written factory is required.

```java
@Inject
@Named("monitor")
MessageStream<String> monitorStream;
```

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

## Programmatic construction

Outside the Micronaut bean graph, both implementations can be instantiated
directly — useful for unit tests, one-off tooling, or apps that don't use
DI:

```java
var redisStream = new RedisMessageStream(jedisPool, myConfig);
// ... or ...
var localStream = new LocalMessageStream();
```

## Migration from earlier releases

Existing code that relied on the auto-registered `@Singleton` beans needs
one of:

1. **Quick fix** — keep the old "single default stream" behaviour by providing
   one `RedisStreamConfig` bean:
   ```java
   @Singleton
   public class MyConfig implements RedisStreamConfig { /* ... */ }
   ```
   Micronaut will then auto-produce a single unqualified `MessageStream`
   bean via the `Default*` variant appropriate for the environment.

2. **Multi-queue setup** — adopt `@EachProperty`+`@EachBean` as shown under
   [Automatic wiring](#automatic-wiring-eachbean).

## Testing

```bash
./gradlew :lib-data-stream-redis:test
```
