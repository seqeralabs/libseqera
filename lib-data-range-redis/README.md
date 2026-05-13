# lib-data-range-redis

Time-based range store abstraction with Redis and local implementations. Useful for implementing cron-like services that need to schedule and retrieve entries based on expiration time.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-range-redis:1.1.0'
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

- `add(String member, double score)` - Add an entry with a timestamp score (overwrites the existing score if the member is already present)
- `addIfLess(String member, double score)` - Add the entry, or update its score only when the new score is strictly less than the current one; returns `true` if applied, `false` if an earlier-or-equal score was kept. Atomic on Redis via `ZADD ... LT CH`
- `getRange(double min, double max, int count)` - Retrieve entries within a score range

The score typically represents epoch seconds, making it easy to schedule entries for future processing.

#### When to use `addIfLess`

Use `addIfLess` when the score represents a **deadline that should only move earlier**. Typical case: a member is scheduled for processing at time `T`, and sustained activity keeps re-adding it. With `add`, every re-add pushes the deadline forward and the entry may never fall inside the polled window. With `addIfLess`, the earliest scheduled time wins and re-adds during activity cannot defer it.

```groovy
// Schedule a task no later than `deadline`; concurrent re-adds cannot push it past that point.
store.addIfLess(taskId, deadline.epochSecond)
```

Prefer plain `add` when the latest score should win (e.g. refresh/heartbeat semantics).

## Configuration

For Redis implementation, configure the Redis URI in your `application.yml`:

```yaml
redis:
  uri: redis://localhost:6379
```

The library automatically selects `RedisRangeProvider` when `redis.uri` is configured, otherwise falls back to `LocalRangeProvider` for development/testing.

## Replica Safety

The Redis implementation is **replica-safe** and supports multiple service instances accessing the store concurrently. The `getRange` method uses a Lua script that atomically fetches and removes entries:

```lua
local elements = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[2], 'LIMIT', ARGV[3], ARGV[4])
if #elements > 0 then
    redis.call('ZREM', KEYS[1], unpack(elements))
end
return elements
```

This ensures:
- **Atomic fetch-and-remove**: No other Redis commands can interleave between fetching and removing entries
- **No duplicate processing**: Each entry is returned to exactly one replica
- **Exactly-once semantics**: When replica A retrieves entries, they are immediately removed, preventing replica B from getting the same entries

This makes the library suitable for distributed cron-like services where multiple replicas poll for expired entries without coordination.

## Testing

```bash
./gradlew :lib-data-range-redis:test
```
