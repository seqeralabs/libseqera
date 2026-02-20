# lib-lock

Distributed locking abstraction with pluggable backends.

## Overview

Provides a simple API for distributed mutex locks with two implementations:
- **LocalLockManager**: In-memory implementation for local development and testing
- **RedisLockManager**: Redis-based implementation for production (in `lib-jedis-lock`)

The correct implementation is automatically selected via Micronaut's conditional bean activation.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-lock:1.0.0'

    // For Redis support in production:
    runtimeOnly 'io.seqera:lib-jedis-lock:1.0.0'
}
```

## Usage

```groovy
import io.seqera.lock.LockManager
import io.seqera.lock.Lock

@Singleton
class MyService {

    @Inject
    LockManager lockManager

    void processWithLock(String resourceId) {
        try (Lock lock = lockManager.acquire("my-resource:" + resourceId, Duration.ofMinutes(5))) {
            // Critical section - only one process can execute this
            processSharedResource()
        } catch (TimeoutException e) {
            // Lock not acquired within timeout
        }
    }

    void tryProcessWithLock(String resourceId) {
        Lock lock = lockManager.tryAcquire("my-resource:" + resourceId)
        if (lock) {
            try {
                processSharedResource()
            } finally {
                lock.release()
            }
        }
    }
}
```

## API

### LockManager

| Method | Description |
|--------|-------------|
| `Lock tryAcquire(String lockKey)` | Non-blocking acquire. Returns `Lock` if acquired, `null` otherwise |
| `Lock acquire(String lockKey, Duration timeout)` | Blocking acquire. Throws `TimeoutException` if not acquired within timeout |

### Lock

| Method | Description |
|--------|-------------|
| `boolean release()` | Release the lock. Returns `true` if released successfully |
| `void close()` | AutoCloseable - calls `release()` |

## Configuration

```yaml
lock:
  acquire-retry-interval: 100ms  # Polling interval when waiting for lock (default: 100ms)
```

## Implementation Selection

| Environment | Implementation | Activation |
|-------------|---------------|------------|
| Local dev (no Redis) | `LocalLockManager` | `@Requires(missingBeans = RedisActivator)` |
| Production (Redis) | `RedisLockManager` | `@Requires(bean = RedisActivator)` |

## Testing

```bash
./gradlew :lib-lock:test
```
