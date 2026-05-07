# lib-data-stream-redis

Message streaming with Redis Streams and an in-memory fallback for persistent
event processing.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-stream-redis:1.3.0'
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
