# lib-jedis-lock

Redis-based distributed locking with automatic expiration and lock coordination.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-jedis-lock:1.0.0'
}
```

## Usage

Distributed mutex locks using Redis for cross-process synchronization:

```groovy
import io.seqera.util.redis.JedisLock
import io.seqera.util.redis.JedisLockManager

// Basic distributed lock with try-with-resources
def jedisPool = new JedisPool("localhost", 6379)

try (def lock = new JedisLock(jedisPool, "my-resource-lock")) {
    if (lock.acquire(Duration.ofSeconds(30))) {
        // Critical section - only one process can execute this
        processSharedResource()
    }
} // lock.close() automatically called, which releases the lock

```

## Testing

```bash
./gradlew :lib-jedis-lock:test
```
