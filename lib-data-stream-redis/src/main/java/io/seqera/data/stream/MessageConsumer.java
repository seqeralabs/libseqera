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

package io.seqera.data.stream;

/**
 * Interface for consuming messages from a message stream.
 *
 * <p>A message consumer defines how individual messages should be processed when they
 * are read from a stream. The consumer's return value determines whether the message
 * was successfully processed and should be acknowledged, or if it failed and may need
 * to be reprocessed.</p>
 *
 * <p>Key characteristics:</p>
 * <ul>
 *   <li><strong>Single Message Processing:</strong> Each invocation processes exactly one message</li>
 *   <li><strong>Acknowledgment Control:</strong> Return value controls message acknowledgment</li>
 *   <li><strong>Error Handling:</strong> Failed processing can trigger redelivery</li>
 *   <li><strong>Stateless:</strong> Should be stateless and thread-safe when possible</li>
 * </ul>
 *
 * <p>Common implementation patterns:</p>
 * <pre>{@code
 * // Simple message processor
 * MessageConsumer<OrderEvent> orderProcessor = order -> {
 *     try {
 *         processOrder(order);
 *         return true; // Success - acknowledge message
 *     } catch (Exception e) {
 *         log.error("Failed to process order", e);
 *         return false; // Failure - don't acknowledge
 *     }
 * };
 *
 * // Conditional processing
 * MessageConsumer<NotificationEvent> notificationFilter = event -> {
 *     if (event.getPriority() == Priority.HIGH) {
 *         sendImmediateNotification(event);
 *         return true; // Processed
 *     }
 *     return false; // Skip - let another consumer handle it
 * };
 *
 * // Batch processing with validation
 * MessageConsumer<DataRecord> batchProcessor = record -> {
 *     if (isValidRecord(record)) {
 *         addToBatch(record);
 *         if (batchIsFull()) {
 *             processBatch();
 *         }
 *         return true; // Successfully added to batch
 *     } else {
 *         log.warn("Invalid record: {}", record);
 *         return true; // Acknowledge to prevent reprocessing invalid data
 *     }
 * };
 * }</pre>
 *
 * <p>Return value semantics:</p>
 * <ul>
 *   <li><strong>{@code true}:</strong> Message processed successfully, acknowledge and remove from stream</li>
 *   <li><strong>{@code false}:</strong> Message not processed, leave available for other consumers</li>
 * </ul>
 *
 * <p>Error handling strategies:</p>
 * <ul>
 *   <li><strong>Retry:</strong> Return {@code false} to allow reprocessing</li>
 *   <li><strong>Dead Letter:</strong> Return {@code true} after logging to prevent infinite retries</li>
 *   <li><strong>Circuit Breaker:</strong> Temporarily return {@code false} when downstream services are unavailable</li>
 * </ul>
 *
 * @param <T> the type of messages that this consumer can process
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see MessageStream#consume(String, MessageConsumer)
 * @see AbstractMessageStream
 */
@FunctionalInterface
public interface MessageConsumer<T> {

    /**
     * Processes a single message from a stream.
     *
     * <p>This method is called by the stream infrastructure when a message is available
     * for processing. The implementation should handle the message according to its
     * business logic and return an appropriate acknowledgment status.</p>
     *
     * <p>Processing guidelines:</p>
     * <ul>
     *   <li><strong>Idempotent:</strong> Should handle duplicate messages gracefully</li>
     *   <li><strong>Fast:</strong> Avoid long-running operations that block other messages</li>
     *   <li><strong>Exception Safe:</strong> Handle exceptions appropriately, don't let them propagate</li>
     *   <li><strong>Logging:</strong> Log important events for debugging and monitoring</li>
     * </ul>
     *
     * <p>Return value meaning:</p>
     * <ul>
     *   <li><strong>{@code true}:</strong> Message was successfully processed and should be acknowledged.
     *       The message will be marked as consumed and will not be delivered to other consumers.</li>
     *   <li><strong>{@code false}:</strong> Message was not processed successfully or was rejected.
     *       The message remains available for consumption by other consumers or for retry.</li>
     * </ul>
     *
     * <p>Common scenarios for returning {@code false}:</p>
     * <ul>
     *   <li>Temporary downstream service unavailability</li>
     *   <li>Message doesn't match consumer's processing criteria</li>
     *   <li>Resource constraints (memory, connections, etc.)</li>
     *   <li>Backpressure from downstream systems</li>
     * </ul>
     *
     * @param message the message to be processed; may be null depending on stream implementation
     * @return {@code true} if the message was successfully processed and should be acknowledged,
     *         {@code false} if the message was not processed and should remain available
     */
    boolean accept(T message);

}
