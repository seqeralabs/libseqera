# Micronaut Redis Cache

A Micronaut cache implementation using the Jedis Redis driver, providing drop-in compatibility with the official Micronaut Redis (Lettuce) cache module.

## Features

- Works with Micronaut's `@Cacheable`, `@CachePut`, `@CacheInvalidate` annotations
- Uses Jedis driver with JedisPool for connection pooling
- Same configuration namespace as the official Lettuce implementation
- Supports expiration policies (expire-after-write, expire-after-access)
- Custom expiration policy support
- Async cache operations via `AsyncCache`

## Installation

Add the dependency to your `build.gradle`:

```groovy
dependencies {
    implementation 'io.seqera:micronaut-redis:1.0.0'
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

## Migration from Lettuce

This module is designed as a drop-in replacement for `micronaut-redis-lettuce` cache. To migrate:

1. Replace the dependency
2. Provide a `JedisPool` bean instead of Lettuce connection
3. Configuration remains the same

## License

Apache License 2.0
