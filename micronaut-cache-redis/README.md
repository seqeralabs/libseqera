# Micronaut Redis Cache

A Micronaut cache implementation using the Jedis Redis driver, providing drop-in compatibility with the official Micronaut Redis (Lettuce) cache module.

## Features

- Works with Micronaut's `@Cacheable`, `@CachePut`, `@CacheInvalidate` annotations
- Uses Jedis driver with JedisPool for connection pooling
- Same configuration namespace as the official Lettuce implementation
- Supports expiration policies (expire-after-write, expire-after-access)
- Custom expiration policy support
- Async cache operations via `AsyncCache`
- Probabilistic early revalidation to prevent cache stampedes (opt-in)

## Installation

Add the dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'io.seqera:micronaut-cache-redis:1.0.0'
}
```

## Configuration

The module uses the same configuration as the official Micronaut Redis cache:

```yaml
redis:
  caches:
    my-cache:
      expire-after-write: 1h
    another-cache:
      expire-after-access: 30m
      invalidate-scan-count: 100
```

### Configuration Options

| Property | Type | Description |
|----------|------|-------------|
| `expire-after-write` | Duration | TTL after writing a value |
| `expire-after-access` | Duration | TTL after accessing a value (touch-based) |
| `expiration-after-write-policy` | String | Custom policy class name |
| `early-revalidation-window` | Duration | Window before expiry for probabilistic refresh (disabled by default) |
| `invalidate-scan-count` | Long | SCAN batch size for invalidateAll (default: 100) |
| `key-serializer` | Class | Custom key serializer |
| `value-serializer` | Class | Custom value serializer |
| `charset` | Charset | Character encoding for keys |

### Default Configuration

You can set defaults for all caches:

```yaml
redis:
  cache:
    expire-after-write: 2h
    charset: UTF-8
  caches:
    my-cache:
      # inherits defaults, can override
      expire-after-write: 30m
```

## Usage

### Provide a JedisPool Bean

The module requires a `JedisPool` bean to be provided by your application:

```java
@Factory
public class RedisFactory {

    @Singleton
    public JedisPool jedisPool() {
        return new JedisPool("localhost", 6379);
    }
}
```

### Using with @Cacheable

```java
@Singleton
public class UserService {

    @Cacheable("users")
    public User findById(Long id) {
        // This will be cached
        return userRepository.findById(id);
    }

    @CachePut("users")
    public User update(Long id, User user) {
        return userRepository.save(user);
    }

    @CacheInvalidate("users")
    public void delete(Long id) {
        userRepository.deleteById(id);
    }
}
```

### Programmatic Access

```java
@Singleton
public class CacheService {

    private final SyncCache<JedisPool> cache;

    public CacheService(@Named("my-cache") SyncCache<JedisPool> cache) {
        this.cache = cache;
    }

    public void example() {
        // Put
        cache.put("key", "value");

        // Get
        Optional<String> value = cache.get("key", String.class);

        // Get with supplier
        String result = cache.get("key", String.class, () -> "default");

        // Put if absent
        Optional<String> existing = cache.putIfAbsent("key", "value");

        // Invalidate
        cache.invalidate("key");
        cache.invalidateAll();

        // Async operations
        cache.async().get("key", String.class)
            .thenAccept(opt -> opt.ifPresent(System.out::println));
    }
}
```

### Custom Value Serializer

The default serializer uses JDK serialization, which requires cached objects to implement `Serializable`. For objects that don't implement `Serializable` (e.g., generated DTOs), you can use a JSON-based serializer.

Create a Jackson serializer:

```java
@Singleton
public class JacksonObjectSerializer implements ObjectSerializer {

    @Inject
    private ObjectMapper objectMapper;

    @Override
    public Optional<byte[]> serialize(Object object) {
        if (object == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.writeValueAsBytes(object));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize object", e);
        }
    }

    @Override
    public <T> Optional<T> deserialize(byte[] bytes, Class<T> requiredType) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(bytes, requiredType));
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize object", e);
        }
    }

    @Override
    public <T> Optional<T> deserialize(byte[] bytes, Argument<T> requiredType) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(bytes,
                    objectMapper.constructType(requiredType.asType())));
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize object", e);
        }
    }
}
```

Configure it:

```yaml
redis:
  caches:
    my-cache:
      value-serializer: com.example.JacksonObjectSerializer
```

### Custom Expiration Policy

Implement `ExpirationAfterWritePolicy` for dynamic TTL:

```java
@Singleton
public class TypeBasedExpirationPolicy implements ExpirationAfterWritePolicy {

    @Override
    public long getExpirationAfterWrite(Object value) {
        if (value instanceof TemporaryData) {
            return Duration.ofMinutes(5).toMillis();
        }
        return Duration.ofHours(1).toMillis();
    }
}
```

Configure it:

```yaml
redis:
  caches:
    my-cache:
      expiration-after-write-policy: com.example.TypeBasedExpirationPolicy
```

## Probabilistic Early Revalidation

This module supports **probabilistic early revalidation**, a lock-free technique to prevent [cache stampedes](https://en.wikipedia.org/wiki/Cache_stampede) in distributed environments. The implementation is based on the approach described in the Cloudflare blog post [_Sometimes I cache_](https://blog.cloudflare.com/sometimes-i-cache/).

### The Problem

When a popular cache entry expires, many concurrent requests simultaneously discover the miss and all attempt to recompute the value. This "stampede" can overwhelm the origin service. Traditional mitigations use distributed locks (e.g., Redis SETNX), but these add latency, require additional infrastructure, and introduce failure modes.

### How It Works

Instead of deterministic locking, this approach uses an exponential probability function to spread revalidation across time *before* the entry actually expires:

```
p(t) = e^(-λ × remainingSeconds)
```

Where `λ = 1 / windowSeconds`.

- **Far from expiry**: probability is near zero — all requests serve the cached value.
- **Close to expiry**: probability ramps up exponentially — a single request probabilistically "wins" and refreshes the cache in the background.
- **At expiry**: probability is 1 — guaranteed refresh (standard cache miss).

The key property is that this is **self-adapting**: high-traffic keys are almost certain to be refreshed before expiry (more dice rolls), while low-traffic keys may expire normally — which is fine since they don't cause stampedes.

### Enabling It

Add `early-revalidation-window` to any cache configuration. This is the duration before expiry during which probabilistic refresh can occur:

```yaml
redis:
  caches:
    my-cache:
      expire-after-write: 30m
      early-revalidation-window: 5m   # start probabilistic refresh 5min before expiry
```

When omitted, the feature is disabled and the cache behaves exactly as before.

### Requirements

Early revalidation only works with the **supplier-based `get`** method (`cache.get(key, type, supplier)` or `@Cacheable`), because the cache needs to know *how* to recompute the value. Plain `get(key, type)` calls without a supplier are unaffected.

### Choosing the Window Size

The window size controls how early the cache starts attempting background refreshes. The probability of a single request triggering a refresh follows `p = e^(-λ × remainingSeconds)` where `λ = 1/windowSeconds`. This means:

- At the **start of the window** (full window remaining), `p ≈ 37%` per request.
- At **half the window** remaining, `p ≈ 61%` per request.
- At **1 second** remaining, probability is near 100%.

The effective refresh rate depends on both the probability and your request rate. With `r` requests/second hitting a key, the expected number of refresh attempts by time `t` into the window is `1 - e^(-r × λ × t)`. In practice this means high-traffic keys are almost certainly refreshed well before expiry, while low-traffic keys may expire normally — which is fine since they don't cause stampedes.

**Practical examples:**

| Use case | TTL | Window | Why |
|----------|-----|--------|-----|
| API token cache, ~100 req/s | `1h` | `5m` | High traffic ensures refresh within seconds of entering the window. Short window avoids unnecessary refreshes during the first 55 minutes. |
| User profile cache, ~5 req/s | `30m` | `5m` | Moderate traffic. With 5 req/s at window start (p≈37%), expect a refresh within the first few seconds. |
| Configuration cache, ~0.1 req/s | `24h` | `1h` | Low traffic needs a wider window to have enough requests for a probabilistic hit. One request every 10s at p≈37% still refreshes within ~30s of entering the window. |
| Rate limit counters, ~1000 req/s | `1m` | `5s` | Very short TTL with very high traffic. Even a tiny window guarantees refresh almost immediately. |

**Rules of thumb:**
- Start with **5–10% of the TTL** and adjust based on observed behavior.
- For **high-traffic keys** (>10 req/s), a small window (1–5% of TTL) is sufficient — more requests means more chances to trigger refresh.
- For **low-traffic keys** (<1 req/s), use a wider window (10–20% of TTL) to ensure at least a few requests fall within it.
- If the **supplier is expensive** (slow DB query, external API call), prefer a wider window to ensure the refresh happens well before expiry, avoiding any chance of a synchronous miss.

### Trade-offs vs. Distributed Locks

| Aspect | Distributed lock | Probabilistic revalidation |
|--------|-----------------|---------------------------|
| External dependency | Redis lock (SETNX) | None — pure math |
| Extra Redis calls | Lock + unlock per miss | One `PTTL` per hit in window |
| Latency on miss | Blocked waiting for lock holder | Returns stale value immediately |
| Failure mode | Lock holder crash → TTL wait | Worst case: a few extra refreshes |
| Cross-instance coordination | Explicit | Implicit via probability |

In the worst case (many JVM instances with concurrent threads hitting the same key near expiry), multiple instances may independently trigger a refresh. Since they all compute and write the same fresh value, the result is correct — just redundant work. This is an acceptable trade-off compared to the complexity and fragility of distributed locking.

## Migration from Lettuce

This module is designed as a drop-in replacement for `micronaut-redis-lettuce` cache. To migrate:

1. Replace the dependency
2. Provide a `JedisPool` bean instead of Lettuce connection
3. Configuration remains the same

## License

Apache License 2.0
