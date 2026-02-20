# lib-jedis-lock

> [!WARNING]
> **Deprecated**: This module is deprecated. Use [lib-lock-redis](../lib-lock-redis) instead, which provides a cleaner API with Micronaut integration and proper connection pool management.

Redis-based distributed locking with automatic expiration and lock coordination.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-jedis-lock:1.0.0'
}
```

## Usage

### Basic Lock Acquisition

```groovy
import io.seqera.util.redis.JedisLockManager
import redis.clients.jedis.JedisPool

def pool = new JedisPool("localhost", 6379)
def conn = pool.getResource()
def manager = new JedisLockManager(conn)

// Try to acquire a lock (non-blocking)
def lock = manager.tryAcquire("my-resource-lock")
if (lock != null) {
    try {
        // Critical section - only one process can execute this
        processSharedResource()
    } finally {
        lock.release()
    }
}

conn.close()
```

### Blocking Acquisition with Timeout

```groovy
import java.time.Duration

def conn = pool.getResource()
def manager = new JedisLockManager(conn)

try {
    // Block until lock is acquired or timeout (throws TimeoutException)
    def lock = manager.acquire("my-resource-lock", Duration.ofSeconds(30))
    try {
        processSharedResource()
    } finally {
        lock.release()
    }
} catch (TimeoutException e) {
    // Handle timeout
}
```

### Custom Lock Configuration

```groovy
def manager = new JedisLockManager(conn)
    .withLockAutoExpireDuration(Duration.ofMillis(200))  // Lock auto-expires after 200ms
    .withAcquireRetryInterval(Duration.ofMillis(10))     // Retry every 10ms when waiting

def lock = manager.acquire("my-lock", Duration.ofSeconds(5))
```

### Deadlock Prevention

Locks automatically expire after the configured duration (default: 5 minutes) to prevent deadlocks if an instance dies while holding a lock.

## Testing

```bash
./gradlew :lib-jedis-lock:test
```
