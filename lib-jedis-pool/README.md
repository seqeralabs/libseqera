# lib-jedis-pool

A Micronaut factory for creating configured JedisPool beans with full Redis URI support, connection pooling, and optional metrics.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-jedis-pool:1.0.0'
}
```

## Features

- Full Redis URI parsing including database selection (`redis://host:6379/1`)
- SSL/TLS support (`rediss://` scheme)
- Configurable connection pool (minIdle, maxIdle, maxTotal)
- Optional password override via configuration
- Micrometer metrics integration (when MeterRegistry is available)
- Conditional activation via RedisActivator

## Configuration

```yaml
redis:
  uri: redis://localhost:6379/1    # Database 1
  password: optional-password       # Optional password override
  pool:
    minIdle: 0                      # Default: 0
    maxIdle: 10                     # Default: 10
    maxTotal: 50                    # Default: 50
  client:
    timeout: 5000                   # Default: 5000ms
```

## Usage

The JedisPool is automatically created when:
1. A `RedisActivator` bean exists (typically via `RedisActivationStrategy`)
2. The `redis.uri` property is configured

```java
@Inject
JedisPool jedisPool;

try (Jedis jedis = jedisPool.getResource()) {
    jedis.set("key", "value");
    String value = jedis.get("key");
}
```

## Metrics

When a `MeterRegistry` is available, the following metrics are registered:

| Metric | Description |
|--------|-------------|
| `jedis.pool.active` | Number of active connections |
| `jedis.pool.idle` | Number of idle connections |
| `jedis.pool.waiters` | Threads waiting for a connection |
| `jedis.pool.created` | Total connections created |
| `jedis.pool.destroyed` | Total connections destroyed |
| `jedis.pool.borrowed` | Total connections borrowed |
| `jedis.pool.returned` | Total connections returned |
| `jedis.pool.max.borrow.wait.millis` | Maximum borrow wait time |
| `jedis.pool.mean.borrow.wait.millis` | Mean borrow wait time |
| `jedis.pool.mean.active.millis` | Mean active duration |
| `jedis.pool.mean.idle.millis` | Mean idle duration |

## Testing

```bash
./gradlew :lib-jedis-pool:test
```
