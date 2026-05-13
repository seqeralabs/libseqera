# lib-data-stream-redis

Message streaming with Redis Streams and local implementations for persistent event processing.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-stream-redis:1.4.0'
}
```

As of version 1.3.0, this library no longer requires Groovy as a runtime dependency.

## Metrics (optional)

`AbstractMessageStream` can publish [Micrometer](https://micrometer.io/) metrics when a
`MeterRegistry` is supplied to the constructor. Micrometer is a `compileOnly` dependency:
consumers that don't opt in have no runtime requirement on `micrometer-core`.

```groovy
class MyStream extends AbstractMessageStream<MyEvent> {
    @Inject
    MyStream(MessageStream<String> target, @Nullable MeterRegistry registry) {
        super(target, registry)   // pass null (or use the 1-arg ctor) to disable metrics
    }
    // ...
}
```

When enabled, the following meters are published (all tagged with `stream` = subclass
`name()` and `stream_id` = the actual Redis key):

| Meter | Type | Tags | Description |
|---|---|---|---|
| `seqera.stream.entries` | Gauge | `stream`, `stream_id` | Current stream backlog |
| `seqera.stream.messages` | Counter | `stream`, `stream_id`, `outcome` | Messages processed (`outcome` ∈ `processed`/`failed`/`errored`) |
| `seqera.stream.processing` | Timer | `stream`, `stream_id`, `outcome` | Per-entry latency with percentile histogram (p25/p50/p75/p95/p99) |

To segregate metrics by application in multi-service deployments, set a common tag at the
`MeterRegistry` boundary (e.g. `micronaut.metrics.tags.application: <name>` in Micronaut).

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

## Testing

```bash
./gradlew :lib-data-stream-redis:test
```