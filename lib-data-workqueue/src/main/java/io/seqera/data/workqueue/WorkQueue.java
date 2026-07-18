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

import java.time.Duration;

/**
 * Interface for a distributed, reliable work queue with competing consumers.
 *
 * <p>A work queue provides the following semantics:</p>
 * <ul>
 *   <li><strong>Competing Consumers:</strong> Multiple consumers pull work from the same queue,
 *       but each message is delivered to exactly one <em>live</em> owner at a time</li>
 *   <li><strong>Acknowledgment:</strong> A message is removed only once it is acknowledged;
 *       otherwise it remains available for redelivery</li>
 *   <li><strong>Lease / visibility timeout:</strong> A delivered message is leased to its owner;
 *       the lease is kept alive by heartbeat renewal for as long as the handler runs</li>
 *   <li><strong>Redelivery &amp; dead-owner reclaim:</strong> If the owner dies (its lease lapses
 *       past the visibility timeout) the message is reclaimed by a peer</li>
 * </ul>
 *
 * <p>Work queues are ideal for:</p>
 * <ul>
 *   <li>Task/job distribution across workers</li>
 *   <li>Reliable command processing with at-least-once delivery</li>
 *   <li>Background processing with dead-consumer failover</li>
 * </ul>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Initialize and offer messages
 * WorkQueue<Event> queue = ...;
 * queue.init("user-events");
 * queue.offer("user-events", new UserLoginEvent(userId, timestamp));
 *
 * // Consume messages
 * MessageConsumer<Event> consumer = event -> {
 *     processEvent(event);
 *     return true; // Acknowledge successful processing
 * };
 *
 * while (hasMoreMessages) {
 *     boolean processed = queue.consume("user-events", consumer);
 *     if (!processed) {
 *         // No messages available, wait before trying again
 *         Thread.sleep(pollInterval);
 *     }
 * }
 * }</pre>
 *
 * @param <M> the type of messages that can be sent through the queue
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see MessageConsumer
 * @see AbstractWorkQueue
 */
public interface WorkQueue<M> {

    /**
     * Initializes the queue with the specified unique identifier.
     *
     * <p>This method prepares the queue for message production and consumption.
     * It should be called before any messages are offered to the queue or consumers
     * are registered. Multiple calls with the same queue ID should be idempotent.</p>
     *
     * <p>Initialization may involve:</p>
     * <ul>
     *   <li>Creating the underlying queue data structure</li>
     *   <li>Setting up consumer group configurations</li>
     *   <li>Establishing connections to distributed storage</li>
     *   <li>Validating queue naming and permissions</li>
     * </ul>
     *
     * @param queueId the unique identifier for the queue; must not be null or empty
     * @throws IllegalArgumentException if queueId is null or empty
     */
    void init(String queueId);

    /**
     * Adds a message to the specified queue.
     *
     * <p>Messages are appended to the queue in the order they are offered.</p>
     *
     * <p>This operation is generally atomic and thread-safe, allowing multiple
     * producers to safely add messages concurrently to the same queue.</p>
     *
     * @param queueId the unique identifier of the target queue; must not be null or empty
     * @param message the message to be added to the queue; may be null depending on implementation
     * @throws IllegalArgumentException if queueId is null or empty
     */
    void offer(String queueId, M message);

    /**
     * Attempts to consume a single message from the queue using the provided consumer.
     *
     * <p>This method attempts to read one message from the queue and pass it to the
     * consumer for processing. The method returns {@code true} if a message was
     * successfully processed, or {@code false} if no message was available or the
     * consumer rejected the message.</p>
     *
     * <p>Message consumption behavior:</p>
     * <ul>
     *   <li><strong>Non-blocking:</strong> Returns immediately if no messages are available</li>
     *   <li><strong>At-least-once:</strong> Messages may be delivered multiple times in failure scenarios</li>
     *   <li><strong>Consumer Control:</strong> Consumer return value determines acknowledgment</li>
     * </ul>
     *
     * <p>Consumer acknowledgment:</p>
     * <ul>
     *   <li>Return {@code true} to acknowledge successful processing</li>
     *   <li>Return {@code false} to indicate processing failure or rejection</li>
     *   <li>Unacknowledged messages may be redelivered to other consumers</li>
     * </ul>
     *
     * @param queueId the unique identifier of the source queue; must not be null or empty
     * @param consumer the message consumer that will process the message; must not be null
     * @return {@code true} if a message was successfully consumed and processed,
     *         {@code false} if no message was available or processing failed
     * @see MessageConsumer#accept(Object)
     */
    default boolean consume(String queueId, MessageConsumer<M> consumer) {
        final Lease<M> lease = receive(queueId);
        if (lease == null) {
            return false;
        }
        final boolean accepted = consumer.accept(lease.message());
        if (accepted) {
            ack(queueId, lease.id());
        }
        else {
            release(queueId, lease.id());
        }
        return accepted;
    }

    /**
     * A single delivered message paired with the token needed to renew, acknowledge
     * or release it. The {@code id} is the queue-implementation specific handle
     * (e.g. the Redis stream entry id) that identifies the delivered entry within
     * its queue.
     *
     * @param <M> the type of the delivered message
     * @param id the implementation specific identifier of the delivered entry
     * @param message the delivered message payload
     */
    record Lease<M>(String id, M message) {}

    /**
     * Receives one message (either newly delivered or reclaimed from a stalled consumer)
     * <strong>without acknowledging</strong> it. The caller becomes responsible for
     * eventually calling {@link #ack(String, String)} once processing terminates, or
     * {@link #release(String, String)} to hand it back for later redelivery.
     *
     * @param queueId the unique identifier of the source queue; must not be null or empty
     * @return a {@link Lease} for the delivered message, or {@code null} if none is available
     */
    Lease<M> receive(String queueId);

    /**
     * Resets the idle time of the given lease (heartbeat), so that an alive consumer
     * keeps ownership of a message for as long as its handler runs. Implementations
     * without a pending-entries list have no lease semantics and treat this as a no-op.
     *
     * @param queueId the unique identifier of the queue; must not be null or empty
     * @param leaseId the identifier of the lease to renew
     */
    void renewLease(String queueId, String leaseId);

    /**
     * Acknowledges terminal processing of the given lease, removing the message from
     * the queue so that it is never redelivered.
     *
     * @param queueId the unique identifier of the queue; must not be null or empty
     * @param leaseId the identifier of the lease to acknowledge
     */
    void ack(String queueId, String leaseId);

    /**
     * Releases the given lease without acknowledging it, so that the message becomes
     * available for redelivery later (a nack; used on shutdown). Implementations
     * without a pending-entries list re-offer the message.
     *
     * @param queueId the unique identifier of the queue; must not be null or empty
     * @param leaseId the identifier of the lease to release
     */
    void release(String queueId, String leaseId);

    /**
     * How often an in-flight lease must be renewed to retain ownership, so an alive
     * consumer is never reclaimed by a peer while its handler is still running. The
     * value is the implementation's own setting (e.g. {@code visibility-timeout / 3} for a
     * Redis consumer group) and MUST be shorter than the reclaim window. Returns
     * {@code null} when the implementation has no lease concept (e.g. in-memory), in
     * which case the caller uses its own default.
     *
     * @return the heartbeat interval, or {@code null} if the implementation has no lease
     */
    default Duration heartbeatInterval() {
        return null;
    }

    /**
     * Upper bound on a single {@code accept()} invocation before its lease is released
     * (safety valve); it does not interrupt the handler thread. Returns {@code null}
     * when the implementation has no lease concept, in which case the caller uses its
     * own default.
     *
     * @return the maximum single-invocation processing time, or {@code null}
     */
    default Duration maxProcessingTime() {
        return null;
    }

    /**
     * Returns the approximate number of messages currently in the specified queue.
     *
     * <p>This method provides a snapshot of the queue length at the time of the call.
     * In a distributed environment with concurrent producers and consumers, the actual
     * number of messages may change immediately after this method returns.</p>
     *
     * <p>Note: This operation may be expensive for large queues or distributed
     * implementations, so it should not be called excessively in performance-critical paths.</p>
     *
     * @param queueId the unique identifier of the queue; must not be null or empty
     * @return the approximate number of messages in the queue; never negative
     * @throws IllegalArgumentException if queueId is null or empty
     */
    int length(String queueId);

}
