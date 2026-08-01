# lib-data-store-state-redis

Distributed state store with Redis and local implementations for managing application state with atomic operations and counters.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-store-state-redis:1.1.0'
}
```

## Usage

Distributed state management with atomic counters and TTL support:

```groovy
@Inject
StateStore<String, MyState> stateStore

// Store state with expiration
def state = new MyState(status: "processing", data: "value")
stateStore.put("task-123", state, Duration.ofMinutes(30))

// Retrieve state
MyState retrieved = stateStore.get("task-123")

// Compare-and-swap: replace only if the stored value still matches the one read.
// Returns false if the key is missing or another writer changed it in between.
def current = stateStore.get("task-123")
def updated = current.withStatus("done")
if( !stateStore.replaceIf("task-123", current, updated) ) {
    // lost the race, re-read and retry
}

// Delete state
stateStore.delete("task-123")

// Atomic counter operations
def params = new CountParams(
    key: "task-123",
    requestId: "req-456",
    field: "retry-count",
    value: 1,
    ttl: Duration.ofHours(1)
)

CountResult result = stateStore.incr(params)
if (result.succeeded()) {
    log.info("Counter incremented to: ${result.value()}")
}

// Check state existence
boolean exists = stateStore.exists("task-123")

// Get keys by pattern
Set<String> keys = stateStore.keys("task-*")
```

## State Entry

Use `StateEntry` for state metadata:

```groovy
class MyState implements StateEntry {
    String status
    String data

    @Override
    String getKey() { return "my-key" }
}
```

## Request Tracking

Implement `RequestIdAware` for request-scoped operations:

```groovy
class TrackedState implements RequestIdAware {
    String requestId
    String data
}
```

## Testing

```bash
./gradlew :lib-data-store-state-redis:test
```
