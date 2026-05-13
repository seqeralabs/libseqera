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

import java.util.function.IntSupplier;

/**
 * Metrics handle consumed by {@code AbstractMessageStream}. Deliberately neutral with
 * respect to Micrometer types so consumers without {@code micrometer-core} on the
 * classpath can still load and instantiate stream subclasses.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link NoopStreamMetrics} — references no Micrometer types; the default
 *       when a stream is constructed without metrics.</li>
 *   <li>{@link MicrometerStreamMetrics} — Micrometer-backed; constructed
 *       explicitly by consumers that have {@code micrometer-core} on the
 *       runtime classpath.</li>
 * </ul>
 *
 * <p>Typical usage from a stream subclass that wants metrics:
 * <pre>{@code
 * @Inject
 * MyStream(MessageStream<String> target, @Nullable MeterRegistry registry) {
 *     super(target, registry != null
 *             ? new MicrometerStreamMetrics(registry, "my-stream")
 *             : NoopStreamMetrics.INSTANCE);
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso
 */
public interface StreamMetrics {

    /**
     * Register a gauge that reports the current backlog of a stream. Called once per
     * stream id, from {@code AbstractMessageStream.addConsumer}.
     *
     * <p>Implementations must hold a strong reference to {@code lengthSupplier};
     * Micrometer's {@code Gauge} keeps only a {@code WeakReference} to the source
     * object, so a transient supplier would be GC'd and the gauge would start
     * reporting {@code NaN}.</p>
     */
    void bindBacklog(String streamId, IntSupplier lengthSupplier);

    /** Start a timing sample. Returns a value suitable for passing back to
     * {@link #recordOutcome(long, String, Outcome)} (nanoseconds, or 0 for no-op). */
    long startSample();

    /** Record the outcome of one processing cycle. {@link Outcome#EMPTY} polls
     *  must not count toward the messages counter or contribute to the timer. */
    void recordOutcome(long startNanos, String streamId, Outcome outcome);
}
