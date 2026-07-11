# lib-data-workqueue-redis

The Redis-backed implementation of the [`lib-data-workqueue`](../lib-data-workqueue)
abstraction. It implements a distributed, reliable work queue on top of Redis Streams
consumer groups: competing consumers, one live owner per key, acknowledgment, a
visibility-timeout lease kept alive by heartbeat renewal, redelivery and dead-owner reclaim.

> **Migrating from `lib-data-stream-redis`?** This module (together with
> `lib-data-workqueue`) is the split/rename of `lib-data-stream-redis` 1.6.0. It keeps the
> exact behaviour (the Redis lease renewal still uses `XCLAIM … JUSTID` internally) and only
> renames the abstraction. See the full guide at
> [`docs/superpowers/specs/2026-07-11-workqueue-rename-migration.md`](../docs/superpowers/specs/2026-07-11-workqueue-rename-migration.md).

## Installation

Add these dependencies to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-workqueue:1.0.0'
    implementation 'io.seqera:lib-data-workqueue-redis:1.0.0'
}
```

## What's here

| Type | Description |
|---|---|
| `RedisWorkQueue` | `implements WorkQueue<String>`; distributed queue over Redis Streams consumer groups. Auto-activated when the `redis` environment is active (requires a configured `JedisPool`). |
| `RedisWorkQueueConfig` | Configuration seam: `getDefaultConsumerGroupName()`, `getVisibilityTimeout()`, `getConsumerWarnTimeout()`, `getHeartbeatInterval()` (default `visibility-timeout / 3`), `getMaxProcessingTime()` (default `15m`), plus the `*Millis()` variants. |

The API/SPI (`WorkQueue`, `WorkQueue.Lease`), the abstract base (`AbstractWorkQueue`), the
in-memory `LocalWorkQueue`, `MessageConsumer` and the metrics seam all live in
[`lib-data-workqueue`](../lib-data-workqueue) — see its README for the full architecture,
metrics and configuration reference.

## Configuration

Provide a `RedisWorkQueueConfig` bean; the `visibility-timeout` property governs the
dead-consumer failover window (mapped to the Redis consumer-group min-idle used by
`XAUTOCLAIM`).

```groovy
@Requires(env = 'redis')
@Singleton
class MyRedisWorkQueueConfig implements RedisWorkQueueConfig {
    @Override String   getDefaultConsumerGroupName() { 'my-service-group' }
    @Override Duration getVisibilityTimeout()        { Duration.ofSeconds(60) }
    @Override Duration getConsumerWarnTimeout()      { Duration.ofSeconds(5) }
}
```

## Testing

Uses Testcontainers to spin up a real Redis, so a running Docker daemon is required.

```bash
./gradlew :lib-data-workqueue-redis:test
```
