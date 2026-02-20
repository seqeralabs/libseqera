# lib-lock-redis

Redis-based distributed locking implementation for [lib-lock](../lib-lock/README.md).

## Overview

This module provides:

1. **High-level API** (`RedisLockManager`): Implements `lib-lock` `LockManager` interface for Micronaut DI
2. **Low-level API** (`JedisLockManager`): Direct Redis lock operations without DI

## Installation

```gradle
dependencies {
    // High-level API with lib-lock abstraction (recommended)
    implementation 'io.seqera:lib-lock:1.0.0'
    runtimeOnly 'io.seqera:lib-lock-redis:1.0.0'

    // Or low-level API only
    implementation 'io.seqera:lib-lock-redis:1.0.0'
}
```

## High-Level API (Recommended)

Uses `lib-lock` abstraction with automatic Micronaut bean injection:

```java
import io.seqera.lock.LockManager;
import io.seqera.lock.Lock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class MyService {

    @Inject
    private LockManager lockManager;  // RedisLockManager injected automatically

    public void process() {
        try (Lock lock = lockManager.acquire("my-lock", Duration.ofMinutes(5))) {
            // Critical section
        } catch (TimeoutException e) {
            // Lock not acquired within timeout
        }
    }
}
```

See [lib-lock README](../lib-lock/README.md) for full API documentation.

### Configuration

```yaml
lock:
  auto-expire-duration: 5m       # Lock auto-expiration (default: 5m)
  acquire-retry-interval: 100ms  # Polling interval (default: 100ms)
```

## Low-Level API

Direct usage without Micronaut DI:

```java
import io.seqera.util.redis.JedisLock;
import io.seqera.util.redis.JedisLockManager;

JedisPool jedisPool = new JedisPool("localhost", 6379);

// Blocking acquire with timeout
try (Jedis jedis = jedisPool.getResource()) {
    JedisLockManager manager = new JedisLockManager(jedis);
    JedisLock lock = manager.acquire("my-resource-lock", Duration.ofSeconds(30));
    try {
        // Critical section - only one process can execute this
        processSharedResource();
    } finally {
        lock.release();
    }
} catch (TimeoutException e) {
    // Lock not acquired within timeout
}

// Non-blocking try-acquire
try (Jedis jedis = jedisPool.getResource()) {
    JedisLockManager manager = new JedisLockManager(jedis);
    JedisLock lock = manager.tryAcquire("my-resource-lock");
    if (lock != null) {
        try {
            processSharedResource();
        } finally {
            lock.release();
        }
    }
}
```

### Low-Level Configuration

```java
JedisLockManager manager = new JedisLockManager(jedis)
    .withLockAutoExpireDuration(Duration.ofMinutes(1))  // default: 5 minutes
    .withAcquireRetryInterval(Duration.ofMillis(50));   // default: 100ms
```

## Testing

```bash
./gradlew :lib-lock-redis:test
```
