# lib-cache-tiered-redis

Two-tier caching implementation with Caffeine (L1) and Redis (L2) for distributed caching.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cache-tiered-redis:1.2.0'
}
```

## Overview

`lib-cache-tiered-redis` provides a two-level caching strategy that combines the speed of local in-memory caching with the consistency of distributed caching:

- **L1 Cache (Caffeine)**: Fast, in-memory local cache for single-instance performance
- **L2 Cache (Redis)**: Distributed cache shared across multiple instances for consistency

## Features

- 🚀 **Fast Local Access**: L1 cache provides microsecond-level response times
- 🌐 **Distributed Consistency**: L2 cache enables cache sharing across instances
- ⏱️ **TTL Support**: Automatic expiration at both cache levels
- 🔒 **Thread-Safe**: Per-key locking ensures safe concurrent access
- 📦 **Flexible Serialization**: Seamless JSON serialization via StringEncodingStrategy
- 🔧 **Configurable**: Customize cache sizes and prefixes per implementation

## Usage

### Basic Implementation

Extend `AbstractTieredCache` to create your own cache:

```java
@Singleton
public class UserCache extends AbstractTieredCache<String, User> {

    public UserCache(L2TieredCache<String, String> l2Cache, MoshiEncodeStrategy<Entry> encoder) {
        super(l2Cache, encoder);
    }

    @Override
    protected String getPrefix() {
        return "users:v1";
    }

    @Override
    protected int getMaxSize() {
        return 10_000;
    }

    @Override
    protected String getName() {
        return "user-cache";
    }
}
```

### L1 TTL

By default an L1 entry lives as long as the value's TTL, so an instance holding a key in L1 does
not see a newer value another instance writes to L2 until that TTL passes. When values are
rewritten during their lifetime, for example refreshed periodically under a long TTL, override
`getL1Ttl()` to bound how long a value is served from L1 before it is read again from L2:

```java
@Override
protected Duration getL1Ttl() {
    return Duration.ofMinutes(5);
}
```

### Using Tiered Keys

For complex keys, implement the `TieredKey` interface:

```java
public class UserCacheKey implements TieredKey {
    private final String tenantId;
    private final String userId;

    public UserCacheKey(String tenantId, String userId) {
        this.tenantId = tenantId;
        this.userId = userId;
    }

    @Override
    public String stableHash() {
        return tenantId + ":" + userId;
    }
}
```

### Cache Operations

```java
// Simple get/put
cache.put("user123", user, Duration.ofHours(1));
User user = cache.get("user123");

// Get with loader function
User user = cache.getOrCompute("user123",
    key -> userService.loadUser(key),
    Duration.ofHours(1)
);

// Get with loader returning value and TTL
User user = cache.getOrCompute("user123",
    key -> {
        User loaded = userService.loadUser(key);
        Duration ttl = loaded.isPremium()
            ? Duration.ofHours(24)
            : Duration.ofHours(1);
        return new Pair<>(loaded, ttl);
    }
);

// Invalidate L1 cache
cache.invalidateAll();
```

### Encoder Configuration

Create a Moshi encoder for your cache entries:

```java
@Singleton
public class UserCacheEncoder extends MoshiEncodeStrategy<AbstractTieredCache.Entry> {

    public UserCacheEncoder() {
        super(createFactory());
    }

    private static JsonAdapter.Factory createFactory() {
        return PolymorphicJsonAdapterFactory
            .of(MoshiSerializable.class, "@type")
            .withSubtype(AbstractTieredCache.Entry.class, "Entry")
            .withSubtype(User.class, "User");
    }
}
```

## Configuration

### Enable Redis

The Redis L2 cache is automatically enabled when the `redis.uri` property is configured:

```yaml
redis:
  uri: redis://localhost:6379
```

### Disable L2 Cache (Development)

For local development or testing without Redis, simply omit the `redis.uri` property. The cache will continue to work with only the L1 tier:

```java
// Works without Redis - L1 only
UserCache cache = new UserCache(null, encoder);
```

## How It Works

1. **Cache Hit Path**:
   - Check L1 (Caffeine) → if found, return immediately
   - On L1 miss, check L2 (Redis) → if found, hydrate L1 and return
   - On L2 miss, invoke loader function (if provided)

2. **Cache Write Path**:
   - Store in both L1 and L2 with TTL
   - L1 expires based on local timestamp
   - L2 expires based on Redis TTL

3. **Thread Safety**:
   - Per-key locks prevent race conditions
   - Multiple keys can be accessed concurrently
   - Same key access is serialized

## Best Practices

- ✅ Use appropriate TTLs based on data volatility
- ✅ Set L1 cache size based on available heap memory
- ✅ Use meaningful cache prefixes to avoid key conflicts
- ✅ Implement `TieredKey` for complex key types
- ✅ Handle null values appropriately in loader functions
- ⚠️ Remember that strong consistency is not guaranteed across instances
- ⚠️ L1 invalidation only affects the local instance

## Configuration

### Enabling Redis Caching

The `RedisL2TieredCache` bean is conditionally loaded based on the presence of a `RedisActivator` bean. To enable Redis caching:

1. **For Micronaut applications**, provide a `RedisActivator` bean:

```java
@Singleton
@Requires(property = "redis.uri")
public class AppRedisActivator implements RedisActivator {
}
```

2. **For testing**, use the `@Requires(env=['redis'])` pattern:

```groovy
@Singleton
@Requires(env=['redis'])
class TestRedisActivation implements RedisActivator {
}
```

The `RedisActivator` marker interface provides a clean way to conditionally enable Redis-based components only when Redis infrastructure is available.

## Dependencies

- Caffeine 3.x (L1 cache)
- Jedis 5.x (Redis client)
- Micronaut Context (dependency injection)
- lib-serde (serialization abstraction)
- lib-serde-moshi (Moshi JSON implementation - optional)
- lib-activator (conditional bean activation)

## License

Apache License 2.0
