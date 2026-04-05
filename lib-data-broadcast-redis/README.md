# lib-data-broadcast-redis

Event broadcast with pub/sub semantics and replay support, backed by Redis Streams or in-memory storage.

Unlike a work queue (where each message is consumed by one consumer and acknowledged), a broadcast delivers every event to **all** registered clients. Late-connecting clients receive the full buffered history before receiving live events.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-broadcast-redis:0.1.0'
}
```

## Usage

### 1. Define your concrete broadcast

Extend `AbstractEventBroadcast<T>` with your event type. Provide a Redis key prefix and a `StringEncodingStrategy` for serialization:

```java
@Singleton
public class MyEventBroadcast extends AbstractEventBroadcast<MyEvent> {

    public MyEventBroadcast() {
        super("myapp:events:", new JacksonEncodingStrategy<>() {});
    }
}
```

`JacksonEncodingStrategy` comes from `lib-serde-jackson` and handles JSON serialization automatically via type inference.

**Note:** If Micronaut's annotation processor cannot generate a bean definition for your class (common when extending classes from other modules), create a factory:

```java
@Factory
public class MyEventBroadcastFactory {
    @Singleton
    public MyEventBroadcast myEventBroadcast() {
        return new MyEventBroadcast();
    }
}
```

### 2. Publish events

```java
@Inject MyEventBroadcast broadcast;

broadcast.offer("session-123", new MyEvent("hello", OffsetDateTime.now()));
```

Events are keyed by a string identifier (e.g. session ID, agent ID). Each key has its own independent event stream.

### 3. Subscribe to events

```java
// Register a client — receives all buffered events immediately, then live events
broadcast.registerClient("session-123", "client-abc", event -> {
    sseEmitter.send(event);
});

// Unregister when the client disconnects
broadcast.unregisterClient("session-123", "client-abc");
```

### 4. Read buffered events

```java
List<MyEvent> events = broadcast.getBufferedEvents("session-123");
```

### 5. Clean up

```java
// Remove all buffered events and client registrations for a key
broadcast.cleanup("session-123");
```

## Local vs Redis

`AbstractEventBroadcast` automatically selects the backing implementation:

| Condition | Implementation | Behavior |
|-----------|---------------|----------|
| No `JedisPool` bean available | `LocalEventBroadcast` | In-memory, single JVM only |
| `JedisPool` bean injected | `RedisEventBroadcast` | Redis Streams, survives restarts, multi-replica |

No configuration or code changes needed — the selection is based on whether a `JedisPool` is present in the Micronaut application context.

### Redis Streams details

When Redis is available:
- **Write**: `XADD` appends events to a Redis Stream keyed by `{prefix}{key}`
- **Replay**: `XRANGE` reads the full stream for late-connecting clients
- **Cleanup**: `DEL` removes the stream
- **No consumer groups** — this is broadcast, not work-queue. Every client gets every event.

### Local details

When Redis is not available:
- Events buffered in `ConcurrentHashMap<String, ArrayList<T>>`
- Per-key locking prevents race conditions between `offer()` and `registerClient()`
- Lost on JVM restart

## API

```java
public interface EventBroadcast<T> {
    void offer(String key, T event);
    void registerClient(String key, String clientId, Consumer<T> callback);
    void unregisterClient(String key, String clientId);
    List<T> getBufferedEvents(String key);
    void cleanup(String key);
}
```

## Testing

```bash
./gradlew :lib-data-broadcast-redis:test
```

Tests run against both `LocalEventBroadcast` and `RedisEventBroadcast` (via testcontainers) with identical test suites to ensure semantic equivalence.

## Dependencies

- `lib-serde` — `StringEncodingStrategy` interface
- `lib-activator` — `RedisActivator` for conditional bean loading
- `jedis` — Redis client (only used when `JedisPool` is available)
