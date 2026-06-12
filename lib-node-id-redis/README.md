# lib-node-id-redis

Distributed node-ordinal assignment for horizontally scaled services. Each replica is assigned a unique ordinal in `[0, capacity)`, intended to seed collision-free distributed ID generators (e.g. a TSID node ID).

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-node-id-redis:1.0.0'
}
```

## Features

- `NodeId` interface exposing the assigned ordinal (`value()`) and the node-ID space size (`capacity()`)
- `RedisNodeId` — ordinals drawn from a shared Redis counter via an atomic Lua script; consecutively started replicas receive consecutive, distinct ordinals (the common case under rolling deployments)
- Counter is wrapped modulo capacity server-side, so it never grows toward 64-bit `INCR` overflow
- Counter key namespaced by the Micronaut application name — distinct apps sharing a Redis instance never contend
- Falls back to a random ordinal when Redis is unreachable, so startup never blocks
- `LocalNodeId` — in-memory implementation for local development and single-instance deployments
- Conditional activation via `RedisActivator`: the Redis implementation loads when a `RedisActivator` bean is present, the local one otherwise

## Configuration

```yaml
micronaut:
  application:
    name: my-app          # Namespaces the Redis counter key

seqera:
  node-id:
    capacity: 1024        # Size of the node-ID space (default: 1024)
```

A `redis.clients.jedis.JedisPool` bean must be available for the Redis implementation (e.g. via [lib-jedis-pool](../lib-jedis-pool/)).

## Usage

```java
import io.seqera.nodeid.NodeId;
import jakarta.inject.Inject;

@Inject
NodeId nodeId;

// Seed a TSID factory with a per-replica node ID
TsidFactory.builder()
    .withNode(nodeId.value(), (int) (Math.log(nodeId.capacity()) / Math.log(2)))
    .build();
```

Align `capacity` with your ID generator's node-bit allocation (e.g. 1024 = 10 node bits).

## Testing

Tests use Testcontainers to run against a real Redis instance:

```bash
./gradlew :lib-node-id-redis:test
```

## License

Apache 2.0
