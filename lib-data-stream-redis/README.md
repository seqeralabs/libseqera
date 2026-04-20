# lib-data-stream-redis

Message streaming with Redis Streams and local implementations for persistent event processing.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-stream-redis:1.3.0'
}
```

As of version 1.3.0, this library no longer requires Groovy as a runtime dependency.

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

## Running multiple streams with independent configurations

`RedisMessageStream` is registered as a default `@Singleton` bean wired via
constructor injection:

```java
public RedisMessageStream(JedisPool pool, RedisStreamConfig config)
```

Applications that need several streams with *different* configurations — for
example, one with a long claim-timeout for slow synchronous work and one with
a short claim-timeout for fast status polling — can construct additional
instances directly and register them as named beans:

```java
@Factory
public class MyStreamFactory {

    @Named("monitor")
    @Singleton
    public MessageStream<String> monitorStream(
            JedisPool pool,
            @Named("monitor") RedisStreamConfig monitorConfig) {
        return new RedisMessageStream(pool, monitorConfig);
    }
}
```

The default bean is still injected on unqualified `@Inject MessageStream<String>`;
additional instances are resolved via `@Named(...)`. Streams sharing the same
Redis instance (or same shard on Redis Cluster) can be produced and consumed
from independently.

## Testing

```bash
./gradlew :lib-data-stream-redis:test
```