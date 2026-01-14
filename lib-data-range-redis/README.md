# lib-data-range-redis

Time-based range store abstraction with Redis and local implementations. Useful for implementing cron-like services that need to schedule and retrieve entries based on expiration time.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-range-redis:1.0.0'
}
```

## Usage

The library provides a `RangeStore` interface for storing entries with a timestamp score, allowing efficient retrieval of entries within a time range. This is particularly useful for implementing scheduled cleanup services or cron-like background tasks.

### Creating a Store

Extend `AbstractRangeStore` to create a concrete store for your use case:

```groovy
@Singleton
@CompileStatic
class ScheduledTaskStore extends AbstractRangeStore {

    ScheduledTaskStore(RangeProvider provider) {
        super(provider)
    }

    @Override
    protected String getKey() {
        return 'scheduled-tasks/v1:'
    }
}
```

### Implementing a Cron Service

Use the store with a scheduler to implement periodic background tasks:

```groovy
@Slf4j
@Context
@CompileStatic
class ScheduledTaskService implements Runnable {

    @Inject
    private TaskScheduler scheduler

    @Inject
    private ScheduledTaskStore store

    @PostConstruct
    private init() {
        // Schedule periodic execution
        scheduler.scheduleWithFixedDelay(
                Duration.ofMinutes(1),  // initial delay
                Duration.ofMinutes(5),  // interval
                this)
    }

    @Override
    void run() {
        final now = Instant.now()
        // Retrieve entries with score <= current time (expired entries)
        final keys = store.getRange(0, now.epochSecond, 100)

        for (String entry : keys) {
            processEntry(entry)
        }
    }

    // Schedule an entry for future processing
    void scheduleTask(String taskId, Duration delay) {
        final expirationSecs = Instant.now().plus(delay).epochSecond
        store.add(taskId, expirationSecs)
    }

    private void processEntry(String entry) {
        // Handle the expired entry
        log.debug "Processing entry: $entry"
    }
}
```

### Key Methods

- `add(String member, double score)` - Add an entry with a timestamp score
- `getRange(double min, double max, int count)` - Retrieve entries within a score range

The score typically represents epoch seconds, making it easy to schedule entries for future processing.

## Configuration

For Redis implementation, configure the Redis URI in your `application.yml`:

```yaml
redis:
  uri: redis://localhost:6379
```

The library automatically selects `RedisRangeProvider` when `redis.uri` is configured, otherwise falls back to `LocalRangeProvider` for development/testing.

## Testing

```bash
./gradlew :lib-data-range-redis:test
```
