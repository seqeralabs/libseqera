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

package io.seqera.data.workqueue;

/**
 * Interface for consuming messages from a work queue.
 *
 * <p>A message consumer defines how individual messages should be processed when they
 * are read from a queue. The consumer returns a {@link Decision} that determines how
 * the message settles: acknowledged and removed, left pending for redelivery, or
 * deferred to a task that settles it later through the {@link MessageLease} handle.</p>
 *
 * <p>Decision semantics:</p>
 * <ul>
 *   <li><strong>{@link Decision#ACK}:</strong> the message was processed; it is
 *       acknowledged and removed from the queue immediately.</li>
 *   <li><strong>{@link Decision#RETRY}:</strong> the message was not processed; it is
 *       left pending and redelivered after the queue's visibility timeout.</li>
 *   <li><strong>{@link Decision#DEFERRED}:</strong> a task now owns the message lease;
 *       the entry stays leased (heartbeated where the underlying queue supports it)
 *       until the task settles it via {@link MessageLease#ack()} or
 *       {@link MessageLease#retry()}. Only a <em>returned</em> {@code DEFERRED}
 *       transfers the lease — a thrown exception settles as {@code RETRY}.</li>
 * </ul>
 *
 * <p>The optional {@link #ready()} admission gate lets a consumer signal backpressure:
 * the dispatcher does not claim messages from a queue while its consumer reports
 * {@code false}.</p>
 *
 * @param <T> the type of messages that this consumer can process
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see WorkQueue#consume(String, MessageConsumer)
 * @see MessageLease
 * @see AbstractWorkQueue
 */
@FunctionalInterface
public interface MessageConsumer<T> {

    /**
     * How a delivered message settles.
     */
    enum Decision {
        /** Settle now: acknowledge and remove the message from the queue. */
        ACK,
        /** Leave pending: the message is redelivered after the visibility timeout. */
        RETRY,
        /** A task owns the lease; it will settle via {@link MessageLease}. */
        DEFERRED
    }

    /**
     * Processes a single message from a queue.
     *
     * <p>This method is called by the queue infrastructure when a message is available
     * for processing. The implementation handles the message according to its business
     * logic and returns the {@link Decision} that settles it — or {@link Decision#DEFERRED}
     * to transfer the settlement responsibility to a task via the given lease.</p>
     *
     * <p>An exception thrown out of this method settles the message as
     * {@link Decision#RETRY}: nothing stays leased, and the message is redelivered on
     * the queue's claim cadence.</p>
     *
     * @param message the message to be processed; may be null depending on queue implementation
     * @param lease the settlement handle for this delivery; only relevant when returning
     *        {@link Decision#DEFERRED}, ignored otherwise
     * @return the settlement decision; must not be null
     */
    Decision accept(T message, MessageLease lease);

    /**
     * Admission gate: the dispatcher does not claim messages from this queue while
     * this method returns {@code false}. A skipped queue counts as an empty poll for
     * the dispatcher's idle pause.
     *
     * @return {@code true} when the consumer can accept a new message
     */
    default boolean ready() {
        return true;
    }

}
