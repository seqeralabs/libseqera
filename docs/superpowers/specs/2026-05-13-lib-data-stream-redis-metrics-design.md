# Design — Micrometer metrics for `lib-data-stream-redis`

- **Status:** Draft
- **Date:** 2026-05-13
- **Author:** Paolo Di Tommaso (with Claude Code)
- **Module:** `lib-data-stream-redis` (target version `1.6.0`)
- **Downstream impact:** `wave`, `sched` (and any other app using `AbstractMessageStream`)

## 1. Goal

Expose the following operational signals from any `AbstractMessageStream`-backed
queue without forcing Micrometer as a runtime dependency on consumers:

- Number of entries currently queued (per stream)
- Total number of items processed
- Throughput rate (derived)
- Error rate (derived)
- Distribution of per-entry processing time (min, q1, median, mean, q3, max, plus p95/p99)

Consumers that opt in (e.g. Wave, Sched) automatically publish to whatever
`MeterRegistry` they have configured — most often `PrometheusMeterRegistry`.

## 2. Non-goals

- Replacing the existing warn-threshold log in `RedisMessageStream:159–163`.
- Tracking Redis-internal metrics (covered separately by `lib-jedis-pool`'s
  `JedisPoolMetricsBinder`).
- Splitting Redis I/O time from consumer-business-logic time. The Timer covers
  the full per-entry lifecycle (claim/read + decode + accept + ack/del). This
  matches what `RedisMessageStream` already times for the warn log and is the
  most operationally actionable signal.
- Adding tenant- or workspace-level cardinality. Stream IDs are a small,
  bounded set per service (e.g. `jobs-pending/v2`, `jobs-queue/v1`).

## 3. Packaging

Add Micrometer as a `compileOnly` dependency in
`lib-data-stream-redis/build.gradle`, mirroring the existing precedent in
`lib-jedis-pool/build.gradle`:

```groovy
compileOnly        "io.micrometer:micrometer-core:1.12.4"
testImplementation "io.micrometer:micrometer-core:1.12.4"
```

Consumers that already pull in `micronaut-micrometer` (Wave, Sched) get
Micrometer on the runtime classpath automatically. Apps without
`micronaut-micrometer` see no behavioural change; the library degrades to a
no-op (see §5).

Bump module version to `1.6.0`. Add a `[release] lib-data-stream-redis@1.6.0`
commit per repo convention. Update `lib-data-stream-redis/README.md` and
`changelog.txt`.

## 4. Architecture

Instrumentation lives entirely in the abstract base class. There is exactly
one touch-point — `AbstractMessageStream` — that:

1. Receives an optional `MeterRegistry` via constructor.
2. Owns a `StreamMetrics` helper that holds all meter handles for the subclass.
3. Registers a backlog **Gauge** when `addConsumer(streamId, …)` is called.
4. Wraps each iteration of the processing loop with a Timer + Counter
   increment tagged by outcome.

Why the abstract layer (rather than a `MessageStream<String>` decorator or
the Redis-specific impl):

- The subclass `name()` (e.g. `"jobs-pending"`) is the stable cross-version
  tag dimension — only the abstract layer sees it. A decorator wrapping the
  shared singleton `MessageStream<String>` doesn't.
- The full per-entry lifecycle (read/claim + decode + accept + ack/del) is
  naturally bounded by the `stream.consume(streamId, …)` call inside
  `processMessages()`. Timing around that call captures everything.
- Local impl is dev-only but gets metrics for free; no extra surface area
  needed in `RedisMessageStream`.

### 4.1 New types

Package `io.seqera.data.stream.metrics` (internal):

- **`StreamMetrics`** — interface (or abstract class) with two
  implementations:
  - `MicrometerStreamMetrics` — references `io.micrometer.core.instrument.*`
    types. Created only when a non-null `MeterRegistry` is supplied.
  - `NoopStreamMetrics` — no Micrometer types referenced in its bytecode. The
    `NOOP` constant used when the registry is absent.

  Public surface:

  ```java
  void bindBacklog(String streamId, java.util.function.IntSupplier lengthSupplier);
  Object startSample();                          // returns Timer.Sample or null
  void recordOutcome(Object sample, String streamId, Outcome outcome);

  enum Outcome { PROCESSED, FAILED, ERRORED, EMPTY }
  ```

  `EMPTY` (no message in the stream this poll) is recorded as a debug counter
  only — *not* added to the per-entry Timer and *not* counted in
  `seqera.stream.messages_total`. This keeps the Timer's
  `_count`/`_sum`/`_max` semantically aligned with "an entry was processed".

- **`AbstractMessageStream`** holds the helper as
  `private final StreamMetrics metrics;` typed as the interface so the
  Micrometer-typed concrete class only loads if a registry was passed in.

### 4.2 Classloader-safety note

The `compileOnly` strategy depends on the Micrometer-touching code being
isolated to one class that is never loaded when the consumer hasn't supplied a
registry. The plan:

- `AbstractMessageStream` imports only the `StreamMetrics` interface and the
  `Outcome` enum from the helper package.
- The constructor decides between `new MicrometerStreamMetrics(registry, name)`
  and `NoopStreamMetrics.INSTANCE` based on `registry == null`. The reference
  to `MicrometerStreamMetrics` is on the cold path; the JVM only resolves it
  when the branch is taken.

During implementation, verify this with a small integration test that loads
`AbstractMessageStream` (via the Local impl) on a classloader without
`micrometer-core` on the classpath — must not throw `NoClassDefFoundError`.
If the JVM eagerly resolves the unused branch on a given JDK, fall back to
indirecting through a tiny SPI loader (`ServiceLoader` or `MethodHandles`).

## 5. API surface

### 5.1 New constructor (additive, backward-compatible)

```java
public abstract class AbstractMessageStream<M> implements Closeable {

    // existing — kept for source/binary compatibility
    protected AbstractMessageStream(MessageStream<String> target) {
        this(target, null);
    }

    // new
    protected AbstractMessageStream(MessageStream<String> target,
                                    @Nullable MeterRegistry registry) {
        this.encoder = createEncodingStrategy();
        this.stream  = target;
        this.metrics = (registry != null)
                ? new MicrometerStreamMetrics(registry, name())
                : NoopStreamMetrics.INSTANCE;
        this.name0   = name() + "-thread-" + count.getAndIncrement();
    }
    …
}
```

The `MeterRegistry` parameter is annotated with
`io.micronaut.core.annotation.Nullable` (the convention used elsewhere in this
repo — see `lib-jedis-pool/JedisPoolFactory`, `lib-cmd-queue-redis/CommandState`,
`lib-data-broadcast-redis/AbstractEventBroadcast`). Javadoc explicitly states
`null` is supported and disables instrumentation.

### 5.2 `addConsumer` — register the backlog gauge

```java
public void addConsumer(String streamId, MessageConsumer<M> consumer) {
    synchronized (listeners) {
        if (listeners.containsKey(streamId)) {
            throw new IllegalStateException("Only one consumer can be defined for each stream …");
        }
        stream.init(streamId);
        listeners.put(streamId, consumer);
        metrics.bindBacklog(streamId, () -> stream.length(streamId)); // new
        if (thread == null) {
            thread = createListenerThread();
        }
    }
}
```

The gauge holds a function reference. Micrometer gauges weakly reference the
target; the lambda captures `this.stream`, which lives for the JVM lifetime.
No leak risk.

### 5.3 `processMessages` — time + count each cycle

The current inner loop:

```java
stream.consume(streamId, (String msg) -> processMessage(msg, consumer, count));
```

becomes:

```java
for (Map.Entry<String, MessageConsumer<M>> entry : listeners.entrySet()) {
    final var streamId = entry.getKey();
    final var consumer = entry.getValue();
    final var sample = metrics.startSample();
    Outcome outcome;
    try {
        // processMessage signals consumer-returned-false vs threw via a holder
        final var status = new ConsumerStatus();
        final boolean processed = stream.consume(streamId,
                msg -> processMessage(msg, consumer, count, status));
        if (!processed) {
            outcome = Outcome.EMPTY;
        } else if (status.failed) {
            outcome = Outcome.FAILED;   // consumer returned false
        } else {
            outcome = Outcome.PROCESSED;
        }
    } catch (Throwable t) {
        outcome = Outcome.ERRORED;       // exception escaped consumer / impl
        throw t;
    } finally {
        metrics.recordOutcome(sample, streamId, outcome);
    }
}
```

`ConsumerStatus` is a tiny private container (`boolean failed`,
`boolean accepted`). The current `processMessage(...)` signature gains the
status arg and sets `status.accepted = consumer.accept(decoded)`; if accepted
is false, sets `status.failed = true`. Return value preserved for upstream
behaviour (`stream.consume` semantics unchanged).

This separation is necessary because today the abstract layer cannot
distinguish "no message available" from "message available but consumer
returned false" — both surface as `stream.consume(...) == false`.

### 5.4 No changes to `MessageStream` interface or its implementations

`RedisMessageStream` and `LocalMessageStream` are untouched. Their warn-log
behaviour stays. The Timer captures the same delta they already measure
internally — intentional duplication; the log is a discrete incident signal,
the metric is a distribution.

## 6. Metric schema

All meters carry the base tag `stream` (the subclass `name()`, e.g.
`jobs-pending`) and `stream_id` (the actual Redis key passed to
`addConsumer`/`offer`, e.g. `jobs-pending/v2`). Cardinality is bounded by
design: a handful of well-known streams per service.

| Meter | Type | Tags | Unit | Description |
|---|---|---|---|---|
| `seqera.stream.entries` | Gauge | `stream`, `stream_id` | entries | Current `XLEN(streamId)` (Redis) or `queue.size()` (Local). |
| `seqera.stream.messages` | Counter | `stream`, `stream_id`, `outcome` | messages | One increment per consume cycle that yielded an entry. `outcome ∈ {processed, failed, errored}`. |
| `seqera.stream.processing` | Timer | `stream`, `stream_id`, `outcome` | seconds | Full per-entry lifecycle: from before `stream.consume(...)` to after it returns. `outcome ∈ {processed, failed, errored}`. |

### 6.1 Timer configuration

```java
Timer.builder("seqera.stream.processing")
        .tag("stream", name)
        .tag("stream_id", streamId)
        .tag("outcome", outcome.tag())
        .description("Per-entry processing time for a message stream")
        .publishPercentiles(0.25, 0.5, 0.75, 0.95, 0.99)   // q1, median, q3, p95, p99
        .publishPercentileHistogram(true)                  // server-side aggregation
        .minimumExpectedValue(Duration.ofMillis(1))
        .maximumExpectedValue(Duration.ofMinutes(5))
        .register(registry);
```

- **min / mean / max** come automatically from Micrometer Timer
  (`_count` + `_sum` give mean; `_max` is the rolling max).
- **q1, median, q3** come from `publishPercentiles`.
- **`publishPercentileHistogram(true)`** also exposes bucket counters so
  Grafana can compute quantiles across replicas with
  `histogram_quantile(le, …)` — important for multi-pod deployments.

### 6.2 EMPTY-poll handling

A poll with no entry available is *not* a processed item. It is recorded
internally (as a debug counter or simply logged), but it does *not* increment
`seqera.stream.messages_total` and does *not* sample the Timer. Doing
otherwise would distort throughput, error rate, and the processing-time
distribution with millions of zero-cost ticks. This decision is intentional
and documented in code comments alongside the outcome enum.

## 7. Multi-app deployments (Wave vs Sched)

The library never tags `application`. Each consumer app supplies it as a
common registry tag at the Micrometer boundary. Recommended Micronaut config:

```yaml
# wave/application.yml
micronaut:
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: wave
```

```yaml
# sched/application.yml
micronaut:
  metrics:
    tags:
      application: sched
```

Equivalent programmatic form:

```java
@Singleton
public class CommonTags implements MeterRegistryConfigurer<MeterRegistry> {
    @Override public void configure(MeterRegistry registry) {
        registry.config().commonTags("application", "wave");
    }
}
```

After this, every metric in the process — including the ones this library
emits, plus `lib-jedis-pool` and JVM/HTTP meters — carries `application=<…>`.
No further wiring in the library.

## 8. Derived PromQL (documentation snippets)

```promql
# throughput, msg/s, per app per stream
rate(seqera_stream_messages_total{outcome="processed"}[1m])

# error rate, msg/s
rate(seqera_stream_messages_total{outcome=~"failed|errored"}[1m])

# error ratio (errors / total)
  sum by (application, stream) (rate(seqera_stream_messages_total{outcome=~"failed|errored"}[5m]))
/ sum by (application, stream) (rate(seqera_stream_messages_total[5m]))

# p95 latency across replicas
histogram_quantile(0.95,
  sum by (le, application, stream)
    (rate(seqera_stream_processing_seconds_bucket{outcome="processed"}[5m])))

# backlog
seqera_stream_entries
```

## 9. Testing

New tests, all under `lib-data-stream-redis/src/test/groovy/io/seqera/data/stream/metrics/`:

- **`AbstractMessageStreamMetricsTest`** — uses `SimpleMeterRegistry` with a
  fake in-memory `MessageStream<String>`; covers:
  - Backlog gauge registered on `addConsumer`, reflects `length()`.
  - Counter increments once per processed/failed/errored cycle, never on
    `EMPTY` polls.
  - Timer records only on non-EMPTY outcomes; `_count` matches counter.
  - With `registry == null`: no meters, no errors, identical functional
    behaviour to today.

- **`RedisMessageStreamMetricsTest`** — Testcontainers Redis, real end-to-end:
  - `XLEN` matches the gauge.
  - Counter advances under load.
  - Timer max ≥ a deliberately-slow consumer's sleep duration.

- **`StreamMetricsClassloaderTest`** — load `AbstractMessageStream` on a
  classloader that excludes `micrometer-core`. Construct a Local-impl
  subclass with the 1-arg constructor. Assert no `NoClassDefFoundError`.

Existing tests are untouched and continue to use the 1-arg constructor;
this verifies the no-op path inherently.

## 10. Downstream migration

### 10.1 Wave

`BaseMessageStream` (Groovy, in `wave/src/main/groovy/io/seqera/wave/service/data/stream/`)
gains an optional `MeterRegistry` parameter and forwards it. Then
`JobPendingQueue`, `JobProcessingQueue` (and any sibling streams) declare it
as an injected constructor arg:

```groovy
@Inject  // optional via @Nullable
JobPendingQueue(MessageStream<String> target,
                JobManagerConfig config,
                @Nullable MeterRegistry registry) {
    super(target, registry)
    …
}
```

Add `micronaut.metrics.tags.application: wave` to Wave's `application.yml`
(or equivalent env-specific file).

### 10.2 Sched

`SchedCommandQueueFactory` updates the factory method to pass a `MeterRegistry`
into the `CommandQueue` constructor (same `@Nullable` pattern). Add
`application: sched` common tag in Sched's `application.yml`.

### 10.3 Other consumers

Any out-of-tree consumer continues to compile unchanged (1-arg constructor
preserved). To opt in, follow the same one-line constructor update.

## 11. Rollout

1. Implement and merge into `lib-data-stream-redis` (`1.6.0`).
2. Verify in Wave staging (Wave's `application.yml` enables metrics first).
3. Update Sched.
4. Add a Grafana dashboard panel set (out of scope here; tracked separately).

## 12. Open implementation items (deliberately deferred to plan)

1. Exact mechanism for the tri-state outcome distinction (small `ConsumerStatus`
   holder vs. tri-state return from `processMessage`). Chosen mechanism must
   not change the semantics of `MessageStream.consume(...)`.
2. Verification step for classloader safety (§4.2) — must run during
   implementation, not after.
3. README and changelog wording for `lib-data-stream-redis@1.6.0`.

## 13. Risk register

| Risk | Mitigation |
|---|---|
| Micrometer class resolution leaks into the no-op path → `NoClassDefFoundError` at runtime for consumers without micrometer-core. | Dedicated classloader test (§9, §4.2). Fall back to SPI/reflection lookup if the simple branch isn't sufficient on a given JDK. |
| Stream-id cardinality explodes (new app uses tenant-scoped streamIds). | Document the "bounded streamId" assumption in the library README. Long-term escape hatch: a config knob to drop the `stream_id` tag — out of scope for `1.6.0`. |
| Timer's bucket count inflates Prometheus payload. | `publishPercentileHistogram(true)` with sensible min/max bounds keeps the bucket count modest. Revisit if the scrape size becomes a concern. |
| Gauge callback throws (e.g., Redis unreachable). | `stream.length(streamId)` is the same call the rest of the system depends on; if it throws, the gauge becomes NaN — acceptable. No special handling. |
