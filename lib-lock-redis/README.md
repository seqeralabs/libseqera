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
seqera:
  lock:
    my-lock:
      auto-expire-duration: 5m       # Lock TTL in Redis (default: 5m)
      acquire-retry-interval: 100ms  # Polling interval for blocking acquire (default: 100ms)
      watchdog-enabled: true         # Auto-renew TTL while held (default: auto, see below)
```

Multiple named lock configurations are supported. Each entry under `seqera.lock` creates a separate `LockManager` bean that can be injected with `@Named`:

```java
@Inject @Named("my-lock") LockManager lockManager;
```

### Watchdog (auto-renewal)

By default, acquired locks run a **watchdog** that periodically renews the Redis key's TTL, preventing expiration while the holder is alive. This is critical for locks held for an indefinite duration (e.g., singleton leader election).

**How it works:**
- On acquire, the key is set with `SET NX PX <ttl>`
- The watchdog renews every `ttl / 3` by calling `PEXPIRE` via a Lua script that checks ownership
- On release (or `close()`), the watchdog is cancelled and the key is deleted

**Example with `auto-expire-duration: 60s`:**
- Lock acquired with TTL = 60s
- Watchdog renews every 20s, resetting the TTL back to 60s
- If the process crashes, the last renewal expires within 60s and another instance can take over
- If the process is alive, the lock is held indefinitely

**Why it matters:** Without the watchdog, a lock with a 60s TTL would silently expire after 60s even if the holder is still running, breaking mutual exclusion. A very long TTL (e.g., 24h) avoids this but delays crash recovery. The watchdog gives both: short crash recovery and indefinite hold while alive.

**Default behavior:** If `watchdog-enabled` is not explicitly set, the watchdog is automatically enabled when `auto-expire-duration` is >= 1 minute, and disabled for shorter TTLs. This avoids unnecessary renewal overhead for short-lived locks while protecting long-held locks by default. Set `watchdog-enabled: false` to explicitly disable auto-renewal, or `true` to force it on regardless of TTL.

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
