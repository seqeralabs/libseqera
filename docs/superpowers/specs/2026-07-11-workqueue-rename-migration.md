# Migration guide — `lib-data-stream-redis` → `lib-data-workqueue` (+ `-redis`)

- **Status:** Proposal / simulation (no code changed yet)
- **Date:** 2026-07-11
- **Why:** the abstraction was named after Redis Streams, but its implemented semantics are a
  **reliable work queue** (competing consumers, one live owner per key, acknowledgment,
  lease/visibility-timeout with heartbeat renewal, redelivery + dead-owner reclaim) — not a
  replayable log/stream. See the naming analysis for prior art (SQS visibility timeout,
  Azure PeekLock/RenewLock, Temporal task-queue + heartbeat, RabbitMQ "Work Queue").

## 1. Module split

The single `lib-data-stream-redis` becomes two modules:

| New module | Contains | Depends on |
|---|---|---|
| **`lib-data-workqueue`** | API/SPI interface, abstract base, **local** impl, consumer, metrics, util. Redis-free. | `lib-serde`, `lib-util-retry`, `lib-activator` (for the local `@Requires`), `micrometer-core` (compileOnly) |
| **`lib-data-workqueue-redis`** | **Redis** impl + Redis config only. | `lib-data-workqueue`, `jedis`, `lib-activator`, `lib-fixtures-redis` (test) |

Both start at **`1.0.0`**. `lib-data-stream-redis` is frozen/deprecated and removed once all
consumers migrate.

## 2. Package remap

| Old | New |
|---|---|
| `io.seqera.data.stream` | `io.seqera.data.workqueue` |
| `io.seqera.data.stream.metrics` | `io.seqera.data.workqueue.metrics` |
| `io.seqera.data.stream.impl` (local) | `io.seqera.data.workqueue` |
| `io.seqera.data.stream.impl` (redis) | `io.seqera.data.workqueue.redis` (in the `-redis` module) |

## 3. Symbol rename map (the "simulation")

### `lib-data-workqueue` (base)

| Old symbol | New symbol | Notes |
|---|---|---|
| `MessageStream<M>` (interface / backing SPI) | **`WorkQueue<M>`** | offer/poll/renew/ack/release/consume/init/length — **method names unchanged** |
| `MessageStream.Lease<M>` | **`WorkQueue.Lease<M>`** | record, unchanged shape |
| `AbstractMessageStream<M>` (typed façade) | **`AbstractWorkQueue<M>`** | dispatcher + pool + lease orchestration |
| `MessageConsumer<T>` | `MessageConsumer<T>` | **kept** — the payload is still a "message" (alt: `QueueConsumer`) |
| `impl.LocalMessageStream` | **`LocalWorkQueue`** | |
| `impl.SleepHelper` | `SleepHelper` | internal util, unchanged |
| `metrics.StreamMetrics` | **`QueueMetrics`** | methods `bindBacklog`/`startSample`/`recordOutcome` unchanged |
| `metrics.NoopStreamMetrics` | **`NoopQueueMetrics`** | `INSTANCE` unchanged |
| `metrics.MicrometerStreamMetrics` | **`MicrometerQueueMetrics`** | |
| `metrics.Outcome` | `Outcome` | enum unchanged (`PROCESSED`/`ACTIVE`/`ERRORED`/`EMPTY`) |

### `lib-data-workqueue-redis`

| Old symbol | New symbol | Notes |
|---|---|---|
| `impl.RedisMessageStream` | **`redis.RedisWorkQueue`** | `implements WorkQueue<String>` |
| `impl.RedisStreamConfig` | **`redis.RedisWorkQueueConfig`** | `getClaimTimeout`→`getVisibilityTimeout` (+`…Millis`), property `claim-timeout`→`visibility-timeout` (§6); `getDefaultConsumerGroupName`/`getHeartbeatInterval`/`getMaxProcessingTime`/`getConsumerWarnTimeout` unchanged |

### Methods / config

The **vocabulary alignment (§6) is applied** (decision accepted): `poll`→`receive`,
`renew`→`renewLease`, `getClaimTimeout`→`getVisibilityTimeout`, evicted lease → "stalled".
Unchanged: `offer`, `ack`, `release`, `consume`, `init`, `length`, `withHandlerExecutor`, and the
protected `pollInterval`/`concurrency`/`heartbeatInterval`/`maxProcessingTime`. Plus a cosmetic,
non-breaking param rename `streamId` → `queueId`.

## 4. Observability — the one runtime-breaking change

Metric names and tags are renamed. **Dashboards / alerts must be updated** as part of migration.

| Old | New |
|---|---|
| `seqera.stream.entries` | `seqera.workqueue.entries` |
| `seqera.stream.messages` | `seqera.workqueue.messages` |
| `seqera.stream.processing` | `seqera.workqueue.processing` |
| tag `stream` | tag `queue` |
| tag `stream_id` | tag `queue_id` |

Constant identifiers (`METRIC_BACKLOG` etc.) keep their names; only their string values change.
The `outcome` tag values (`processed`/`active`/`errored`) are unchanged.

## 5. Per-consumer migration

Replace the dependency `io.seqera:lib-data-stream-redis` with
`io.seqera:lib-data-workqueue` **and** `io.seqera:lib-data-workqueue-redis`, then apply §3.

### `lib-cmd-queue-redis`
- `CommandQueue`: `extends AbstractMessageStream<CommandMsg>` → `AbstractWorkQueue<CommandMsg>`;
  imports `MessageStream`/`MessageConsumer`/`StreamMetrics`/`NoopStreamMetrics`/`MicrometerStreamMetrics`
  → the `WorkQueue`/`QueueMetrics` equivalents.
- `TestCommandQueue`, `CommandServiceTest`: same import swaps.

### `sched`
- `SchedCommandQueue` (extends `CommandQueue`) — indirect; recompile.
- `SchedRedisStreamConfig implements RedisStreamConfig` → `RedisWorkQueueConfig`
  (rename the class to `SchedRedisWorkQueueConfig` for consistency).
- `SchedCommandQueueFactory`: `MicrometerStreamMetrics`/`MessageStream` → new names.
- `LocalComputeLifecycleIntegrationTest`: import swaps.
- Update sched dashboards using `seqera.stream.*` / `stream`,`stream_id` tags.

### `wave`
- `service.data.stream.BaseMessageStream extends AbstractMessageStream` → `AbstractWorkQueue`
  (consider renaming to `BaseWorkQueue` + package `service.data.workqueue`).
- `JobPendingQueue`, `JobProcessingQueue`: import/type swaps (already "*Queue" — good fit).
- `RedisStreamConfigBean implements RedisStreamConfig` → `RedisWorkQueueConfig`.
- Update wave dashboards using `seqera.stream.*`.
- ⚠️ wave is the heaviest user (7× `MessageStream`); regression-test before shipping.

## 6. Optional (fresh-library) vocabulary alignment

A new library is the cheapest moment to also adopt the widely-recognized lease vocabulary
(prior art in §Why). **Not part of the mechanical rename** — opt in only if desired, as it
changes method/config names:

| Current | Prior-art rename | Source |
|---|---|---|
| `getClaimTimeout()` | `visibilityTimeout` | SQS / Celery / Azure |
| `renew(...)` | `renewLease(...)` | Azure RenewLock / Temporal |
| `poll(...)` | `receive(...)` | SQS / Pub/Sub |
| lease evicted past `maxProcessingTime` | "stalled" | BullMQ |

## 7. Rollout

1. Create `lib-data-workqueue` + `lib-data-workqueue-redis` (port the current 1.6.0 async/lease
   implementation, renamed per §3). Ship `1.0.0` of both.
2. Migrate consumers in dependency order: `lib-cmd-queue-redis` → `sched`, `wave`.
3. Update dashboards/alerts (§4) alongside each service's deploy.
4. Remove `lib-data-stream-redis` once no consumer references it.

Do this **after** #84/#85 land — it renames the very module those PRs modify, so stacking it
would churn both. The misleading javadoc on the current classes should be corrected regardless.
