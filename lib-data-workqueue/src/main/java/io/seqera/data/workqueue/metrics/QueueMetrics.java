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

package io.seqera.data.workqueue.metrics;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Metrics handle consumed by {@code AbstractWorkQueue}. Deliberately neutral with
 * respect to Micrometer types so consumers without {@code micrometer-core} on the
 * classpath can still load and instantiate queue subclasses.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link NoopQueueMetrics} — references no Micrometer types; the default
 *       when a queue is constructed without metrics.</li>
 *   <li>{@link MicrometerQueueMetrics} — Micrometer-backed; constructed
 *       explicitly by consumers that have {@code micrometer-core} on the
 *       runtime classpath.</li>
 * </ul>
 *
 * <p>Typical usage from a queue subclass that wants metrics:
 * <pre>{@code
 * @Inject
 * MyQueue(WorkQueue<String> target, @Nullable MeterRegistry registry) {
 *     super(target, registry != null
 *             ? new MicrometerQueueMetrics(registry, "my-queue")
 *             : NoopQueueMetrics.INSTANCE);
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso
 */
public interface QueueMetrics {

    /**
     * Register a gauge that reports the current backlog of a queue. Called once per
     * queue id, from {@code AbstractWorkQueue.addConsumer}.
     *
     * <p>Implementations must hold a strong reference to {@code lengthSupplier};
     * Micrometer's {@code Gauge} keeps only a {@code WeakReference} to the source
     * object, so a transient supplier would be GC'd and the gauge would start
     * reporting {@code NaN}.</p>
     */
    void bindBacklog(String queueId, IntSupplier lengthSupplier);

    /** Start a timing sample. Returns a value suitable for passing back to
     * {@link #recordOutcome(long, String, Outcome)} (nanoseconds, or 0 for no-op). */
    long startSample();

    /** Record the outcome of one processing cycle. {@link Outcome#EMPTY} polls
     *  must not count toward the messages counter or contribute to the timer. */
    void recordOutcome(long startNanos, String queueId, Outcome outcome);

    /** Record one lease-renewal tick: its duration, the number of entries currently
     *  leased and the age of the oldest lease (leased count and max age are published
     *  as gauges by instrumented implementations). */
    default void renewTick(long durationNanos, int leasedCount, long maxLeaseAgeNanos) { }

    /** Record a failed lease-renewal round-trip (retried on the next tick). */
    default void renewError() { }

    /**
     * Bind a liveness probe for the lease-renewal scheduler: the age (nanos) of the
     * last COMPLETED renewal tick. A stuck tick — e.g. a renewal thread blocked on an
     * exhausted connection pool — shows as unbounded growth here while every other
     * renewal signal stays silent (a blocked borrow never throws, so renewError never
     * fires and the tick-overrun warn never runs). Alert on age above a few renewal
     * periods.
     */
    default void bindRenewalLiveness(LongSupplier ageNanos) { }

    /** Record a lease found to be owned by another consumer during the renewal
     *  ownership check — the residual duplicate-execution window, made observable. */
    default void leaseLost() { }

    /** Record a lease dropped by the renewal age backstop — a settlement path
     *  that never ran; the claim cycle recovers the entry. */
    default void leaseLeak() { }

    /** Record a poll skipped because the queue's consumer reported not
     *  {@code ready()} — an admission-blocked replica, distinct from an idle one. */
    default void saturated(String queueId) { }

    /** Record a delivery whose consumer returned {@code DEFERRED} — a task took
     *  the message lease and settles it later. */
    default void deferred(String queueId) { }
}
