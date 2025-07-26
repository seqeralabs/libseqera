/*
 * Copyright 2025, Seqera Labs
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

package io.seqera.data.stream
/**
 * Interface for a distributed message stream that supports real-time event processing.
 * 
 * <p>A message stream differs from a traditional message queue in several key ways:</p>
 * <ul>
 *   <li><strong>Persistent Log:</strong> Messages are stored as an append-only log that can be replayed</li>
 *   <li><strong>Multiple Consumers:</strong> Multiple consumers can read from the same stream independently</li>
 *   <li><strong>Ordered Delivery:</strong> Messages are delivered in the order they were added</li>
 *   <li><strong>Consumer Groups:</strong> Consumers can be grouped for load balancing and fault tolerance</li>
 *   <li><strong>Stream Replay:</strong> Consumers can start reading from any point in the stream history</li>
 * </ul>
 * 
 * <p>Message streams are ideal for:</p>
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
 * MessageStream<Event> stream = ...;
 * stream.init("user-events");
 * stream.offer("user-events", new UserLoginEvent(userId, timestamp));
 * 
 * // Consume messages asynchronously
 * MessageConsumer<Event> consumer = event -> {
 *     processEvent(event);
 *     return true; // Acknowledge successful processing
 * };
 * 
 * while (hasMoreMessages) {
 *     boolean processed = stream.consume("user-events", consumer);
 *     if (!processed) {
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
 *   <li>Stream retention policies</li>
 *   <li>Dead letter handling for failed messages</li>
 * </ul>
 *
 * @param <M> the type of messages that can be sent through the stream
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see MessageConsumer
 * @see AbstractMessageStream
 */
interface MessageStream<M> {

    /**
     * Initializes the stream with the specified unique identifier.
     * 
     * <p>This method prepares the stream for message production and consumption.
     * It should be called before any messages are offered to the stream or consumers
     * are registered. Multiple calls with the same stream ID should be idempotent.</p>
     * 
     * <p>Initialization may involve:</p>
     * <ul>
     *   <li>Creating the underlying stream data structure</li>
     *   <li>Setting up consumer group configurations</li>
     *   <li>Establishing connections to distributed storage</li>
     *   <li>Validating stream naming and permissions</li>
     * </ul>
     *
     * @param streamId the unique identifier for the stream; must not be null or empty
     * @throws IllegalArgumentException if streamId is null or empty
     */
    void init(String streamId)

    /**
     * Adds a message to the specified stream.
     * 
     * <p>Messages are appended to the stream in the order they are offered, creating
     * an immutable, ordered log of events. Once added, messages typically cannot be
     * modified or deleted, ensuring data integrity and enabling stream replay.</p>
     * 
     * <p>This operation is generally atomic and thread-safe, allowing multiple
     * producers to safely add messages concurrently to the same stream.</p>
     * 
     * <p>Message properties:</p>
     * <ul>
     *   <li><strong>Ordering:</strong> Messages maintain their insertion order</li>
     *   <li><strong>Durability:</strong> Messages are persisted for later consumption</li>
     *   <li><strong>Uniqueness:</strong> Each message receives a unique sequence number or ID</li>
     *   <li><strong>Timestamp:</strong> Messages are typically timestamped upon arrival</li>
     * </ul>
     *
     * @param streamId the unique identifier of the target stream; must not be null or empty
     * @param message the message to be added to the stream; may be null depending on implementation
     * @throws IllegalArgumentException if streamId is null or empty
     */
    void offer(String streamId, M message)

    /**
     * Attempts to consume a single message from the stream using the provided consumer.
     * 
     * <p>This method attempts to read one message from the stream and pass it to the
     * consumer for processing. The method returns {@code true} if a message was
     * successfully processed, or {@code false} if no message was available or the
     * consumer rejected the message.</p>
     * 
     * <p>Message consumption behavior:</p>
     * <ul>
     *   <li><strong>Non-blocking:</strong> Returns immediately if no messages are available</li>
     *   <li><strong>Ordered:</strong> Messages are delivered in stream order</li>
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
     * @param streamId the unique identifier of the source stream; must not be null or empty
     * @param consumer the message consumer that will process the message; must not be null
     * @return {@code true} if a message was successfully consumed and processed,
     *         {@code false} if no message was available or processing failed
     * @see MessageConsumer#accept(Object)
     */
    boolean consume(String streamId, MessageConsumer<M> consumer)

    /**
     * Returns the approximate number of messages currently in the specified stream.
     * 
     * <p>This method provides a snapshot of the stream length at the time of the call.
     * In a distributed environment with concurrent producers and consumers, the actual
     * number of messages may change immediately after this method returns.</p>
     * 
     * <p>Common use cases include:</p>
     * <ul>
     *   <li>Monitoring stream backlog and processing rates</li>
     *   <li>Capacity planning and resource allocation</li>
     *   <li>Alerting on stream growth beyond expected thresholds</li>
     *   <li>Load balancing decisions across consumer instances</li>
     *   <li>Testing and debugging stream behavior</li>
     * </ul>
     * 
     * <p>Note: This operation may be expensive for large streams or distributed
     * implementations, so it should not be called excessively in performance-critical paths.</p>
     *
     * @param streamId the unique identifier of the stream; must not be null or empty
     * @return the approximate number of messages in the stream; never negative
     * @throws IllegalArgumentException if streamId is null or empty
     */
    int length(String streamId)

}
