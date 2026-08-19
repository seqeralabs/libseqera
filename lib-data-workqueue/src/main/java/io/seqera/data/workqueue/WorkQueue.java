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
 * Interface for a distributed work queue that supports real-time event processing.
 *
 * <p>A work queue of this kind differs from a fire-and-forget message queue in several
 * key ways:</p>
 * <ul>
 *   <li><strong>Persistent Log:</strong> Messages are stored as an append-only log that can be replayed</li>
 *   <li><strong>Multiple Consumers:</strong> Multiple consumers can read from the same queue independently</li>
 *   <li><strong>Ordered Delivery:</strong> Messages are delivered in the order they were added</li>
 *   <li><strong>Consumer Groups:</strong> Consumers can be grouped for load balancing and fault tolerance</li>
 *   <li><strong>Log Replay:</strong> Consumers can start reading from any point in the queue history</li>
 * </ul>
 *
 * <p>Work queues are ideal for:</p>
 * <ul>
 *   <li>Event sourcing and audit logging</li>
 *   <li>Real-time data processing and analytics</li>
 *   <li>Microservice event communication</li>
 *   <li>Activity feeds and notification systems</li>
 *   <li>Change data capture (CDC) systems</li>
 * </ul>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Initialize and offer messages
 * WorkQueue<Event> queue = ...;
 * queue.init("user-events");
 * queue.offer("user-events", new UserLoginEvent(userId, timestamp));
 *
 * // Consume messages asynchronously
 * MessageConsumer<Event> consumer = (event, lease) -> {
 *     processEvent(event);
 *     return MessageConsumer.Decision.ACK; // Acknowledge successful processing
 * };
 *
 * while (hasMoreMessages) {
 *     MessageConsumer.Decision decision = queue.consume("user-events", consumer);
 *     if (decision == null) {
 *         // No messages available, wait before trying again
 *         Thread.sleep(pollInterval);
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations may provide additional features such as:</p>
 * <ul>
 *   <li>Message partitioning for scalability</li>
 *   <li>Consumer group management</li>
 *   <li>Queue retention policies</li>
 *   <li>Dead letter handling for failed messages</li>
 * </ul>
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
     * <p>Messages are appended to the queue in the order they are offered, creating
     * an immutable, ordered log of events. Once added, messages typically cannot be
     * modified or deleted, ensuring data integrity and enabling replay.</p>
     *
     * <p>This operation is generally atomic and thread-safe, allowing multiple
     * producers to safely add messages concurrently to the same queue.</p>
     *
     * <p>Message properties:</p>
     * <ul>
     *   <li><strong>Ordering:</strong> Messages maintain their insertion order</li>
     *   <li><strong>Durability:</strong> Messages are persisted for later consumption</li>
     *   <li><strong>Uniqueness:</strong> Each message receives a unique sequence number or ID</li>
     *   <li><strong>Timestamp:</strong> Messages are typically timestamped upon arrival</li>
     * </ul>
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
     * consumer for processing, together with a {@link MessageLease} settlement handle.
     * The consumer's {@link MessageConsumer.Decision} controls how the message settles.</p>
     *
     * <p>Message consumption behavior:</p>
     * <ul>
     *   <li><strong>Non-blocking:</strong> Returns immediately if no messages are available</li>
     *   <li><strong>Ordered:</strong> Messages are delivered in queue order</li>
     *   <li><strong>At-least-once:</strong> Messages may be delivered multiple times in failure scenarios</li>
     *   <li><strong>Consumer Control:</strong> Consumer decision determines settlement</li>
     * </ul>
     *
     * <p>Settlement semantics:</p>
     * <ul>
     *   <li>{@link MessageConsumer.Decision#ACK} — the message is acknowledged and removed</li>
     *   <li>{@link MessageConsumer.Decision#RETRY} — the message stays pending and is
     *       redelivered after the visibility timeout</li>
     *   <li>{@link MessageConsumer.Decision#DEFERRED} — the message stays leased until
     *       the consumer's task settles it via {@link MessageLease}</li>
     *   <li>An exception thrown by the consumer settles the message as {@code RETRY}
     *       and propagates to the caller</li>
     * </ul>
     *
     * @param queueId the unique identifier of the source queue; must not be null or empty
     * @param consumer the message consumer that will process the message; must not be null
     * @return the consumer's {@link MessageConsumer.Decision}, or {@code null} when no
     *         message was available
     * @see MessageConsumer#accept(Object, MessageLease)
     */
    MessageConsumer.Decision consume(String queueId, MessageConsumer<M> consumer);

    /**
     * Returns the approximate number of messages currently in the specified queue.
     *
     * <p>This method provides a snapshot of the queue length at the time of the call.
     * In a distributed environment with concurrent producers and consumers, the actual
     * number of messages may change immediately after this method returns.</p>
     *
     * <p>Common use cases include:</p>
     * <ul>
     *   <li>Monitoring queue backlog and processing rates</li>
     *   <li>Capacity planning and resource allocation</li>
     *   <li>Alerting on queue growth beyond expected thresholds</li>
     *   <li>Load balancing decisions across consumer instances</li>
     *   <li>Testing and debugging queue behavior</li>
     * </ul>
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
