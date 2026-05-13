/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.seqera.data.stream.metrics;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micrometer-backed {@link StreamMetrics}. Constructed explicitly by consumers that
 * have {@code io.micrometer:micrometer-core} on their runtime classpath.
 *
 * <p>This class is the only main-source class in the library that references
 * Micrometer types. It is intentionally not referenced from {@code AbstractMessageStream}
 * so that the library remains loadable on classpaths without Micrometer (consumers
 * that don't need metrics use {@link NoopStreamMetrics#INSTANCE}).</p>
 *
 * <p>Published meters (all tagged with {@code stream=<streamName>} and
 * {@code stream_id=<streamId>}):
 * <ul>
 *   <li>{@code seqera.stream.entries} (Gauge) — current backlog</li>
 *   <li>{@code seqera.stream.messages} (Counter; tag {@code outcome=processed|failed|errored})</li>
 *   <li>{@code seqera.stream.processing} (Timer with percentile histogram; same outcome tag)</li>
 * </ul>
 */
public final class MicrometerStreamMetrics implements StreamMetrics {

    private static final Logger log = LoggerFactory.getLogger(MicrometerStreamMetrics.class);

    public static final String METRIC_BACKLOG    = "seqera.stream.entries";
    public static final String METRIC_MESSAGES   = "seqera.stream.messages";
    public static final String METRIC_PROCESSING = "seqera.stream.processing";

    private final MeterRegistry registry;
    private final String streamName;
    // Strong references to gauge value-suppliers. Micrometer's Gauge holds the source
    // object through a WeakReference; without this map the supplier lambda would be
    // GC-eligible the moment bindBacklog returns and the gauge would report NaN.
    private final ConcurrentMap<String, IntSupplier> backlogSuppliers = new ConcurrentHashMap<>();

    public MicrometerStreamMetrics(MeterRegistry registry, String streamName) {
        this.registry = registry;
        this.streamName = streamName;
    }

    @Override
    public void bindBacklog(String streamId, IntSupplier lengthSupplier) {
        // Micrometer caches gauges by (name + tags), so a second register(...) call
        // returns the originally-registered gauge — still bound to the first supplier.
        // Skip duplicates explicitly so the silent failure mode is obvious.
        if (backlogSuppliers.putIfAbsent(streamId, lengthSupplier) != null) {
            log.warn("Backlog gauge already bound for stream={} stream_id={} — ignoring duplicate bind",
                    streamName, streamId);
            return;
        }
        Gauge.builder(METRIC_BACKLOG, lengthSupplier, (ToDoubleFunction<IntSupplier>) IntSupplier::getAsInt)
                .description("Current number of entries available on the stream")
                .tag("stream", streamName)
                .tag("stream_id", streamId)
                .baseUnit("entries")
                .register(registry);
    }

    @Override
    public long startSample() {
        return System.nanoTime();
    }

    @Override
    public void recordOutcome(long startNanos, String streamId, Outcome outcome) {
        if (outcome == Outcome.EMPTY) {
            return;
        }

        Counter.builder(METRIC_MESSAGES)
                .description("Total messages processed by the stream, by outcome")
                .tag("stream", streamName)
                .tag("stream_id", streamId)
                .tag("outcome", outcome.tag())
                .baseUnit("messages")
                .register(registry)
                .increment();

        Timer.builder(METRIC_PROCESSING)
                .description("Per-entry processing time for a message stream")
                .tag("stream", streamName)
                .tag("stream_id", streamId)
                .tag("outcome", outcome.tag())
                .publishPercentiles(0.25, 0.5, 0.75, 0.95, 0.99)
                .publishPercentileHistogram(true)
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
