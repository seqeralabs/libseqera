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

Define a store by extending `AbstractStateStore`, providing the key prefix, the default
entry time-to-live and an encoding strategy for the stored type:

```groovy
@Singleton
@CompileStatic
class MyStateStore extends AbstractStateStore<MyState> {

    // any StringEncodingStrategy will do; this one comes from lib-serde-moshi
    MyStateStore(StateProvider<String,String> provider) {
        super(provider, new MoshiEncodeStrategy<MyState>() {})
    }

    @Override
    protected String getPrefix() { 'my-state/v1' }

    @Override
    protected Duration getDuration() { Duration.ofMinutes(30) }
}
```

The provider is injected automatically: `RedisStateProvider` when the application supplies
a `RedisActivator` bean, `LocalStateProvider` (in-memory, for development and tests) when
the `redis.uri` property is missing.

```groovy
@Inject
MyStateStore store

def state = new MyState(status: "processing")

// store a value, with the default TTL or an explicit one
store.put("task-123", state)
store.put("task-123", state, Duration.ofMinutes(5))

// store only if the key is absent
boolean stored = store.putIfAbsent("task-123", state)

// retrieve, or null when missing or expired
MyState found = store.get("task-123")

// remove a single entry, or every entry of the underlying provider
store.remove("task-123")
store.clear()
```

### Compare-and-swap

`replaceIf` writes only when the stored value still equals the one previously read, so a
read-modify-write can detect a concurrent writer instead of silently overwriting it. It
returns `false` when the key is missing or the current value differs:

```groovy
def current = store.get("task-123")
def updated = current.withStatus("done")
if( !store.replaceIf("task-123", current, updated) ) {
    // another writer got there first — re-read and retry
}

// same, but resetting the entry TTL; the two-argument form keeps the remaining one
store.replaceIf("task-123", current, updated, Duration.ofMinutes(5))
```

The comparison is made on the serialized form, so the encoding strategy must be
deterministic. On Redis this is a single Lua script, and requires Redis 6.0 or later.

### Atomic counters

`putIfAbsentAndCount` stores the entry only if absent and, when it does, increments an
associated counter and injects the new value into the record. It assumes the entry is
serialized as a JSON object holding a `count` attribute — override `counterKey` and
`counterScript` to change the counter key or the way the value is patched:

```groovy
CountResult<MyState> result = store.putIfAbsentAndCount("task-123", state)
if( result.succeed )
    log.info "Stored with count=${result.count}"
else
    log.info "Already present: ${result.value}"
```

## Request tracking

A stored type implementing `RequestIdAware` is additionally indexed by its request id,
which makes it retrievable without knowing the entry key:

```groovy
class MyState implements RequestIdAware {
    String requestId
    String status
}

MyState found = store.findByRequestId("req-456")
```

`StateEntry<K>` is a marker interface for records that carry their own key; the store does
not require it, but consumers can use it to keep the key with the record.

## Testing

```bash
./gradlew :lib-data-store-state-redis:test
```
