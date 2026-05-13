# Design — Micrometer metrics for `lib-data-stream-redis`

- **Status:** Implemented (PR [#70](https://github.com/seqeralabs/libseqera/pull/70))
- **Date:** 2026-05-13 (spec) · 2026-05-13 (implementation)
- **Author:** Paolo Di Tommaso (with Claude Code)
- **Module:** `lib-data-stream-redis` (released as `1.4.0`)
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

Bump module version to `1.4.0`. Add a `[release] lib-data-stream-redis@1.4.0`
commit per repo convention. Update `lib-data-stream-redis/README.md` and
`changelog.txt`.

## 4. Architecture

Instrumentation lives entirely in the abstract base class. There is exactly
one touch-point — `AbstractMessageStream` — that:

1. Receives an optional `StreamMetrics` handle via constructor (never
   `MeterRegistry` directly — see §4.2).
2. Registers a backlog **Gauge** when `addConsumer(streamId, …)` is called.
3. Wraps each iteration of the processing loop with a Timer + Counter
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

Package `io.seqera.data.stream.metrics` (public, part of the library API):

- **`StreamMetrics`** — interface, Micrometer-neutral. Methods:

  ```java
  void bindBacklog(String streamId, java.util.function.IntSupplier lengthSupplier);
  long startSample();                                          // nanoseconds, 0 for no-op
  void recordOutcome(long startNanos, String streamId, Outcome outcome);
  ```

- **`MicrometerStreamMetrics implements StreamMetrics`** — the only class in
  the library main sources that references
  `io.micrometer.core.instrument.*`. Constructed explicitly by consumers that
  have `micrometer-core` on their runtime classpath.

- **`NoopStreamMetrics implements StreamMetrics`** — references no Micrometer
  types in its bytecode. Exposed via `NoopStreamMetrics.INSTANCE`.

- **`Outcome`** — public enum `{ PROCESSED, FAILED, ERRORED, EMPTY }`.

  `EMPTY` (no message in the stream this poll) is **not** counted in
  `seqera.stream.messages_total` and does **not** contribute to the Timer.
  This keeps the Timer's `_count`/`_sum`/`_max` semantically aligned with
  "an entry was processed".

Package `io.seqera.data.stream`:

- **`AbstractMessageStream`** holds the helper as
  `private final StreamMetrics metrics;` typed as the interface so the
  Micrometer-typed concrete class only loads if a consumer explicitly
  constructs it.

No additional types are needed in the `io.seqera.data.stream` package: the
existing `processMessage` already increments the shared `AtomicInteger count`
on every invocation, so the polling loop can read that counter before and
after `stream.consume(...)` to derive the outcome — see §5.3.

### 4.2 Classloader-safety: why `AbstractMessageStream` does not take `MeterRegistry`

**Original plan (revised during implementation):** `AbstractMessageStream`
would take `@Nullable MeterRegistry registry` directly in the constructor,
relying on HotSpot's lazy descriptor resolution to keep Micrometer out of
the loaded class set when the consumer passed `null`.

**Why this was wrong:** the dedicated classloader test (§9) demonstrated
that Java reflection — `Class.getDeclaredConstructors()` and friends —
resolves *all* parameter types of *every* declared method, regardless of
whether the method is ever invoked. Real-world consumers that don't supply
a `MeterRegistry` still trigger this resolution through:

- Groovy's `MetaClass` machinery when an instance is touched in Groovy
  code (e.g. `.classLoader`, `.class`, `instanceof` checks against
  Groovy-decorated targets).
- Micronaut's runtime introspection of bean classes.
- Debuggers and IDE class browsers.
- Any framework that enumerates constructors (Spock, Jackson, etc.).

`StreamMetricsClassloaderTest` reproduced the failure with a single line:
`abstractCls.classLoader.is(isolated)` triggered
`getDeclaredConstructors()` and threw
`NoClassDefFoundError: io/micrometer/core/instrument/MeterRegistry`.

**Resolution:** `AbstractMessageStream` references only `StreamMetrics`
(neutral) and `NoopStreamMetrics` (no Micrometer types). `MeterRegistry`
never appears in any method signature reachable from `AbstractMessageStream`.
Consumers that want Micrometer construct `MicrometerStreamMetrics`
themselves at call sites that already have Micrometer in scope; the type is
loaded only when that constructor runs.

The classloader test is now permanent (§9.3) and pinned to verify this
boundary stays clean.

## 5. API surface

### 5.1 New constructor (additive, backward-compatible)

```java
public abstract class AbstractMessageStream<M> implements Closeable {

    // existing — kept for source/binary compatibility
    protected AbstractMessageStream(MessageStream<String> target) {
        this(target, NoopStreamMetrics.INSTANCE);
    }

    // new
    protected AbstractMessageStream(MessageStream<String> target,
                                    @Nullable StreamMetrics metrics) {
        this.encoder = createEncodingStrategy();
        this.stream  = target;
        this.metrics = (metrics != null) ? metrics : NoopStreamMetrics.INSTANCE;
        this.name0   = name() + "-thread-" + count.getAndIncrement();
    }
    …
}
```

The `StreamMetrics` parameter is annotated with
`io.micronaut.core.annotation.Nullable` (the convention used elsewhere in this
repo — see `lib-jedis-pool/JedisPoolFactory`, `lib-cmd-queue-redis/CommandState`,
`lib-data-broadcast-redis/AbstractEventBroadcast`). Javadoc explicitly states
`null` is supported and disables instrumentation.

Consumer-side wiring for Micrometer:

```groovy
import io.seqera.data.stream.metrics.MicrometerStreamMetrics

@Inject
MyStream(MessageStream<String> target, @Nullable MeterRegistry registry) {
    super(target, registry != null
            ? new MicrometerStreamMetrics(registry, 'my-stream')
            : null)
}
```

The `MeterRegistry` parameter lives in the subclass constructor (where the
consumer already opted into Micrometer); it never reaches
`AbstractMessageStream`'s signature.

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

The current inner loop body:

```java
stream.consume(streamId, (String msg) -> processMessage(msg, consumer, count));
```

is extracted into a private `consumeOne(streamId, consumer, count)` helper so
the polling loop reads as it did before the metrics work:

```java
for (Map.Entry<String, MessageConsumer<M>> entry : listeners.entrySet()) {
    consumeOne(entry.getKey(), entry.getValue(), count);
}
```

The helper records the per-cycle outcome:

```java
private void consumeOne(String streamId, MessageConsumer<M> consumer, AtomicInteger count) {
    final long sample = metrics.startSample();
    final int countBefore = count.get();
    Outcome outcome = Outcome.EMPTY;
    try {
        final boolean accepted = stream.consume(streamId,
                (String msg) -> processMessage(msg, consumer, count));
        if (count.get() != countBefore) {
            outcome = accepted ? Outcome.PROCESSED : Outcome.FAILED;
        }
    }
    catch (Throwable t) {
        outcome = Outcome.ERRORED;       // exception escaped consumer / impl
        throw t;
    }
    finally {
        metrics.recordOutcome(sample, streamId, outcome);
    }
}
```

**Outcome detection.** The original 3-arg `processMessage` already increments
the shared `AtomicInteger count` on every invocation (it's been doing this
since before the metrics work — the outer loop uses `count.get() == 0` to
decide whether to sleep). That gives the polling loop a free side channel
for distinguishing "no message available" from "message available but
consumer returned false":

| `count` delta | `stream.consume(...)` returned | outcome |
|---|---|---|
| 0 (lambda never ran)   | `false`                | `EMPTY`     |
| >0                     | `true`                 | `PROCESSED` |
| >0                     | `false`                | `FAILED`    |
| (any — caught above)   | `Throwable`            | `ERRORED`   |

No additional holder class or 4-arg overload is needed: `processMessage`'s
signature is unchanged, and there is no `ConsumerStatus` type. (An earlier
draft of this spec proposed both — they were dropped during implementation
when it became clear that the existing `count` AtomicInteger carried enough
signal on its own.)

`outcome` is given a default value of `Outcome.EMPTY` at declaration to
satisfy Java's definite-assignment analysis (the `try` block could throw
before reaching any assignment).

**Side-effect: `LocalMessageStream` consumer exceptions now classify as
`FAILED` rather than `EMPTY`.** `LocalMessageStream.consume` catches consumer
exceptions internally, logs at debug, and returns `false`. With the
count-delta logic, the lambda *did* run (count was incremented before the
throw), so the outcome is `FAILED`, not `EMPTY`. That matches user
expectation better than the holder-based design would have. `RedisMessageStream`
propagates consumer exceptions, so on Redis the same scenario surfaces as
`ERRORED`. This difference is inherent to the underlying impls (pre-existing,
not introduced by metrics) and is called out in the library README.

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

### 6.0 Strong-reference note for the backlog gauge

Micrometer's `Gauge` holds the source object through a `WeakReference`.
`MicrometerStreamMetrics` therefore keeps each backlog `IntSupplier` in a
`ConcurrentMap<streamId, IntSupplier>` field; without this, the lambda
becomes GC-eligible the moment `bindBacklog` returns and the gauge starts
reporting `NaN`. This was caught by the unit test on first run; the inline
comment in `MicrometerStreamMetrics.java` documents the gotcha.

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

### 9.1 `AbstractMessageStreamMetricsTest` (Groovy/Spock)

`lib-data-stream-redis/src/test/groovy/io/seqera/data/stream/AbstractMessageStreamMetricsTest.groovy`
— uses `SimpleMeterRegistry` with `LocalMessageStream`. Four scenarios, all
passing:

- Backlog gauge registered on `addConsumer`, eventually reflects the
  drained length and the test's consumer queue size.
- Counter increments once per processed cycle; Timer `_count` matches.
- Consumer returning `false` lands as `outcome=failed`; subsequent retry
  succeeds as `outcome=processed`.
- 1-arg constructor (no metrics): messages flow, no meters are registered.

### 9.2 `StreamMetricsClassloaderTest` (Groovy/Spock)

`lib-data-stream-redis/src/test/groovy/io/seqera/data/stream/StreamMetricsClassloaderTest.groovy`
— constructs an isolated `URLClassLoader` from the current test classpath
minus any jar whose filename starts with `micrometer-core`, with
`ClassLoader.getPlatformClassLoader()` as parent so JDK classes resolve but
application classes can only come through the explicit URL list. Two cases,
both passing:

- Sanity check: `Class.forName('io.micrometer.core.instrument.MeterRegistry', …)`
  throws `ClassNotFoundException` on the isolated loader.
- `AbstractMessageStream`, `MessageStream`, `LocalMessageStream`, and the
  pure-Java `TestPlainStream` subclass load and instantiate via the 1-arg
  constructor through the isolated loader, and `close()` runs end-to-end.
  No `NoClassDefFoundError`/`LinkageError`.

Backing fixture: `TestPlainStream.java` (in `src/test/java/`) — a minimal
Java subclass of `AbstractMessageStream<String>`. Plain Java rather than
Groovy so the isolation test doesn't have to reason about the Groovy
runtime resolving auxiliary classes.

### 9.3 Existing tests

The existing `LocalMessageStreamTest`, `RedisMessageStreamTest`, and
`AbstractMessageStreamLocalTest` are untouched and continue to use the
1-arg constructor. They inherently verify that the no-op path preserves
prior behaviour.

A dedicated `RedisMessageStreamMetricsTest` (Testcontainers) was considered
and deferred: `RedisMessageStream` is a transparent pass-through for the
metrics layer (the gauge calls `XLEN`, the Timer/Counter live in the
abstract layer); the existing `RedisMessageStreamTest` still exercises the
Redis path end-to-end, and the metrics unit tests cover the wiring. Can be
added if a future bug demonstrates the need.

## 10. Downstream migration

### 10.1 Wave

`BaseMessageStream` (Groovy, in `wave/src/main/groovy/io/seqera/wave/service/data/stream/`)
gains an optional `StreamMetrics` parameter and forwards it. Then
`JobPendingQueue`, `JobProcessingQueue` (and any sibling streams) construct
the metrics handle from a `@Nullable MeterRegistry`:

```groovy
import io.seqera.data.stream.metrics.MicrometerStreamMetrics

@Inject
JobPendingQueue(MessageStream<String> target,
                JobManagerConfig config,
                @Nullable MeterRegistry registry) {
    super(target, registry != null
            ? new MicrometerStreamMetrics(registry, 'jobs-pending')
            : null)
    …
}
```

Add `micronaut.metrics.tags.application: wave` to Wave's `application.yml`
(or equivalent env-specific file).

### 10.2 Sched

`SchedCommandQueueFactory` updates the factory method to construct a
`MicrometerStreamMetrics` and pass it into the `CommandQueue` constructor
(same `@Nullable` pattern). Add `application: sched` common tag in Sched's
`application.yml`.

### 10.3 Other consumers

Any out-of-tree consumer continues to compile and run unchanged: the 1-arg
constructor is preserved and behaves identically to the previous library
version (no meters registered). To opt in, follow the same constructor
update as Wave/Sched.

## 11. Rollout

1. ✅ Implement and merge into `lib-data-stream-redis` (`1.4.0`) — PR #70.
2. Verify in Wave staging (Wave's `application.yml` enables metrics first).
3. Update Sched.
4. Add a Grafana dashboard panel set (out of scope here; tracked separately).

## 12. Resolved during implementation

1. **Tri-state outcome distinction** — resolved by reading the existing
   `AtomicInteger count` before and after `stream.consume(...)`. The original
   3-arg `processMessage` already increments `count` on every invocation
   (used by the outer loop to decide whether to sleep), so the polling loop
   gets the "did the lambda run?" signal for free. No additional holder class
   or 4-arg `processMessage` overload was needed. See §5.3 for the truth
   table.
2. **Classloader safety** — the original plan (`MeterRegistry` in
   `AbstractMessageStream`'s constructor, relying on HotSpot lazy
   resolution) was shown to be unsafe under reflection. Replaced with the
   neutral `StreamMetrics` interface (see §4.2 for the full story).
   `StreamMetricsClassloaderTest` is the permanent regression guard.
3. **Backlog gauge GC retention** — discovered on first test run, fixed
   with a `ConcurrentMap<streamId, IntSupplier>` in
   `MicrometerStreamMetrics` (see §6.0).
4. **README and changelog wording for `1.4.0`** — landed in PR #70.

## 13. Risk register

| Risk | Mitigation |
|---|---|
| Stream-id cardinality explodes (new app uses tenant-scoped streamIds). | Documented "bounded streamId" assumption in the library README. Long-term escape hatch: a config knob to drop the `stream_id` tag — out of scope for `1.4.0`. |
| Timer's bucket count inflates Prometheus payload. | `publishPercentileHistogram(true)` with sensible min/max bounds keeps the bucket count modest. Revisit if the scrape size becomes a concern. |
| Gauge callback throws (e.g., Redis unreachable). | `stream.length(streamId)` is the same call the rest of the system depends on; if it throws, the gauge becomes NaN — acceptable. No special handling. |
| Consumer accidentally references `MeterRegistry` from `AbstractMessageStream` again, re-introducing the classloader problem. | `StreamMetricsClassloaderTest` is permanent and fails CI if the boundary is broken. |
