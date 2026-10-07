# lib-jedis-pool

A Micronaut factory for creating configured JedisPool beans with full Redis URI support, connection pooling, and optional metrics.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-jedis-pool:1.3.0'
}
```

## Features

- Full Redis URI parsing including database selection (`redis://host:6379/1`)
- SSL/TLS support (`rediss://` scheme)
- Configurable connection pool (minIdle, maxIdle, maxTotal, testOnBorrow)
- Optional password override via configuration
- `cluster-primary` mode for single-shard cluster-mode servers with replicas (e.g. AWS MemoryDB)
- Micrometer metrics integration (when MeterRegistry is available)
- Conditional activation via RedisActivator

## Configuration

```yaml
redis:
  uri: redis://localhost:6379/1    # Database 1
  mode: standalone                  # Default: standalone — or cluster-primary, see below
  password: optional-password       # Optional password override
  pool:
    minIdle: 0                      # Default: 0
    maxIdle: 10                     # Default: 10
    maxTotal: 50                    # Default: 50
    testOnBorrow: false             # Default: false — validate connections on borrow
    maxWait: -1                     # Default: -1 (unbounded) — max millis to wait for a connection
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

## Cluster-primary mode

AWS MemoryDB (and any cluster-mode Redis) exposes a discovery endpoint that resolves to every node of
the shard, replicas included. A standalone client that lands on a replica gets `MOVED` for every keyed
command, and a replica still answers `PING`. With `redis.mode: cluster-primary`:

- the host in `redis.uri` is used only to discover the shard primary (`CLUSTER SHARDS`, each candidate
  confirmed with a probe);
- pooled connections are validated on every borrow (`testOnBorrow` is forced on) by reading a probe key,
  `jedis-pool:primary-probe`: the primary answers, a replica answers `MOVED`. After a failover stale
  connections are evicted and new ones go to the new primary;
- `JedisConnector.connect()` opens a dedicated connection to the primary for blocking commands.

Constraints: a single shard only, database 0 only, and no cross-slot multi-key commands. The Redis user
needs `@slow` (for `CLUSTER SHARDS`) and read access to the probe key; `@admin`/`@dangerous` are not needed.
Commands in flight during a failover (a few seconds) fail and must be retried by the caller.

```yaml
redis:
  uri: rediss://scheduler@clustercfg.my-cluster.xxxxxx.memorydb.eu-west-2.amazonaws.com:6379
  mode: cluster-primary
  password: ${REDIS_PASSWORD}
```

## Dedicated connections

Pool connections time out blocking commands after `redis.client.timeout`. For `XREAD BLOCK` or
`SUBSCRIBE`, inject `JedisConnector` and open a dedicated connection, which honors `redis.mode`, TLS and
credentials:

```java
@Inject
JedisConnector connector;

try (Jedis jedis = connector.connect()) {
    jedis.subscribe(listener, channel);
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
