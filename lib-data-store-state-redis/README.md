# lib-data-store-state-redis

Distributed state store with Redis and local implementations for managing application state with atomic operations and counters.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-store-state-redis:1.2.0'
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

A store opts into optimistic concurrency by extending `VersionedStateStore` instead of
`AbstractStateStore`; its value type must implement `VersionAware` — it carries its own
version. Read the value, transform it (the version rides along), and write it back
conditionally with `replaceIf`:

```groovy
@Singleton
@CompileStatic
class MyStateStore extends VersionedStateStore<MyState> {

    // the provider must also implement VersionProvider - both implementations do
    MyStateStore(StateProvider<String,String> provider) {
        super(provider, new MoshiEncodeStrategy<MyState>() {})
    }

    // getPrefix / getDuration as above
}

class MyState implements VersionAware<MyState> {
    // ... domain fields ...
    long version

    @Override
    long version() { return version }

    @Override
    MyState withVersion(long v) { new MyState(/* same fields */, v) }
}

def current = store.get("task-123")            // version as stored
def updated = current.withStatus("done")       // transitions preserve the version
if( !store.replaceIf("task-123", updated) ) {  // lands only if the entry is unchanged
    // another writer got there first — re-read and retry
}

// same, but with an explicit TTL; the two-argument form resets it to getDuration(),
// like every other write
store.replaceIf("task-123", updated, Duration.ofMinutes(5))
```

The version is the write witness: the swap is refused when the entry was written after
the read the value derives from. Versions are unique time-sorted identifiers (TSID)
stamped by the store on **every** write — `put` included — so any write invalidates
every outstanding witness; callers never assign versions, they carry forward the one
they read. The whole compare-and-swap is a single atomic server-side call: the store
frames every versioned write with a leading `{"@v":N}` JSON property, and the swap peeks
the stored version from that frame — no payload parsing, no expected value on the wire,
no re-serialization, cost independent of the value size. The frame is transparent to the
encoding strategy: it is stripped symmetrically on read — the decoder receives exactly
the payload the encoder produced — and its version is injected into the decoded value
through `withVersion`, making the frame the single source of truth for the version; the
value type does not need to serialize its version field. Versioned values must serialize
to a JSON object, and their leading `@v` property is reserved for the store; a plain
`AbstractStateStore` never frames nor strips, so a genuine `@v` property of a
non-versioned value is always preserved, and on a versioned store a head that merely
resembles a frame but cannot be one (version digits that do not fit a long) never fails
a read. Values stored before versioning carry no frame, count as version `0`, and are
adopted by their first successful replace. Requires Redis 6.0 or later.

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
