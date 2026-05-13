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

package io.seqera.data.stream;

import java.util.function.IntSupplier;

/**
 * Internal abstraction over the metric handles emitted by {@link AbstractMessageStream}.
 *
 * <p>This interface is deliberately neutral with respect to Micrometer types so that
 * {@link AbstractMessageStream} can be loaded by a classloader that does not include
 * {@code io.micrometer:micrometer-core} on its classpath. The Micrometer-typed
 * implementation is isolated in {@link MicrometerStreamMetrics} and only loaded when a
 * non-null {@code MeterRegistry} is supplied; the no-op implementation
 * ({@link NoopStreamMetrics}) is used otherwise.</p>
 */
interface StreamMetrics {

    /**
     * Register a gauge that reports the current backlog (length) of a stream.
     * Called once per stream id, from {@code addConsumer}.
     */
    void bindBacklog(String streamId, IntSupplier lengthSupplier);

    /**
     * Start a timing sample.
     *
     * @return the start time in nanoseconds (or {@code 0L} for the no-op impl).
     */
    long startSample();

    /**
     * Record the outcome of one processing cycle. {@link Outcome#EMPTY} polls are
     * not counted in the message counter and do not contribute to the timer.
     */
    void recordOutcome(long startNanos, String streamId, Outcome outcome);
}
