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

package io.seqera.data.queue

import java.time.Duration

import groovy.transform.CompileStatic
/**
 * Interface for a distributed message queue modeled as a blocking queue with FIFO semantics.
 * 
 * <p>This interface provides a contract for reliable message passing between distributed components.
 * Messages are delivered in first-in-first-out (FIFO) order, and the queue supports both blocking
 * and non-blocking operations. Each queue is identified by a unique target name, allowing multiple
 * independent queues to coexist within the same system.</p>
 * 
 * <p>Key characteristics:</p>
 * <ul>
 *   <li><strong>Distributed:</strong> Messages can be produced and consumed across multiple processes or nodes</li>
 *   <li><strong>Persistent:</strong> Messages survive process restarts (implementation dependent)</li>
 *   <li><strong>FIFO Ordering:</strong> Messages are delivered in the order they were added</li>
 *   <li><strong>Blocking Operations:</strong> Consumers can wait for messages to become available</li>
 *   <li><strong>Thread-Safe:</strong> Safe for concurrent access from multiple threads</li>
 * </ul>
 * 
 * <p>Common usage patterns:</p>
 * <pre>{@code
 * // Producer
 * MessageQueue<TaskMessage> queue = ...;
 * queue.offer("work-queue", new TaskMessage("process-data"));
 * 
 * // Consumer (non-blocking)
 * TaskMessage message = queue.poll("work-queue");
 * if (message != null) {
 *     processMessage(message);
 * }
 * 
 * // Consumer (blocking with timeout)
 * TaskMessage message = queue.poll("work-queue", Duration.ofSeconds(30));
 * if (message != null) {
 *     processMessage(message);
 * }
 * }</pre>
 * 
 * <p>Implementations may provide additional features such as:</p>
 * <ul>
 *   <li>Message persistence and durability guarantees</li>
 *   <li>Dead letter queues for failed messages</li>
 *   <li>Message prioritization</li>
 *   <li>Queue capacity limits and backpressure</li>
 * </ul>
 *
 * @param <M> the type of messages that can be sent through the queue
 * 
 * @author Jordi Deu-Pons <jordi@seqera.io>
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see AbstractMessageQueue
 */
@CompileStatic
interface MessageQueue<M> {

    /**
     * Inserts the specified message at the tail of the named queue.
     * 
     * <p>This operation is typically non-blocking and returns immediately after the message
     * has been added to the queue. The message will be available for consumption by any
     * consumer polling the same target queue.</p>
     * 
     * @param target the unique identifier for the target queue; must not be null or empty
     * @param value the message to be added to the queue; it must be not null
     */
    void offer(String target, M value)

    /**
     * Retrieves and removes the head message from the named queue, returning immediately.
     * 
     * <p>This is a non-blocking operation that returns {@code null} if no message is currently
     * available in the queue. Messages are returned in FIFO order - the message that has been
     * in the queue the longest will be returned first.</p>
     * 
     * <p>This method is useful for polling patterns where the consumer wants to check for
     * messages without blocking if none are available.</p>
     *
     * @param target the unique identifier for the target queue; must not be null or empty
     * @return the head message from the queue, or {@code null} if the queue is empty
     * @throws IllegalArgumentException if the target is null or empty
     */
    M poll(String target)

    /**
     * Retrieves and removes the head message from the named queue, waiting up to the 
     * specified timeout if necessary for a message to become available.
     * 
     * <p>This is a blocking operation that will wait for the specified duration for a message
     * to become available. If a message becomes available within the timeout period, it will
     * be returned immediately. If the timeout expires before a message is available, the method
     * returns {@code null}.</p>
     * 
     * <p>This method is ideal for consumer applications that want to wait for work items
     * without busy-waiting, while still maintaining responsiveness with a reasonable timeout.</p>
     * 
     * <p>The blocking behavior may be interrupted if the current thread is interrupted,
     * in which case an {@code InterruptedException} may be thrown or the thread's
     * interrupted status may be set.</p>
     *
     * @param target the unique identifier for the target queue; must not be null or empty
     * @param timeout the maximum time to wait for a message to become available; must not be null
     * @return the head message from the queue, or {@code null} if no message becomes 
     *         available within the timeout period
     */
    M poll(String target, Duration timeout)

    /**
     * Returns the approximate number of messages currently in the named queue.
     * 
     * <p>This method provides a snapshot of the queue length at the time of the call.
     * In a concurrent environment, the actual number of messages may change immediately
     * after this method returns, so the value should be treated as an approximation.</p>
     * 
     * <p>Note: Some implementations may find this operation expensive, especially for
     * distributed queues, so it should not be called excessively in performance-critical paths.</p>
     *
     * @param target the unique identifier for the target queue; must not be null or empty
     * @return the approximate number of messages in the queue; never negative
     */
    int length(String target)
}




