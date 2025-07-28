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
/**
 * Interface for sending messages to connected clients or endpoints.
 * 
 * <p>This interface abstracts the mechanism for delivering messages to individual recipients,
 * such as WebSocket clients, HTTP endpoints, or other communication channels. It is typically
 * used in conjunction with message queues to implement push-based notification systems.</p>
 * 
 * <p>Common implementations include:</p>
 * <ul>
 *   <li>WebSocket message senders for real-time communication</li>
 *   <li>HTTP POST senders for webhook delivery</li>
 *   <li>Email or SMS notification senders</li>
 *   <li>In-memory event bus senders</li>
 * </ul>
 * 
 * <p>Usage example with WebSocket:</p>
 * <pre>{@code
 * MessageSender<NotificationMessage> sender = new WebSocketMessageSender(session);
 * sender.send(new NotificationMessage("User logged in", "INFO"));
 * }</pre>
 * 
 * <p>Implementations should handle connection failures gracefully and may implement
 * retry logic, dead letter handling, or other reliability patterns as appropriate
 * for their specific transport mechanism.</p>
 *
 * @param <M> the type of messages that can be sent
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see AbstractMessageQueue
 */
interface MessageSender<M> {

    /**
     * Sends a message to the connected endpoint or client.
     * 
     * <p>This method attempts to deliver the specified message to the target recipient.
     * The behavior varies depending on the implementation:</p>
     * 
     * <ul>
     *   <li><strong>Synchronous:</strong> Method blocks until message is sent or fails</li>
     *   <li><strong>Asynchronous:</strong> Method returns immediately, message sent in background</li>
     *   <li><strong>Best-effort:</strong> Message may be lost if connection is unavailable</li>
     *   <li><strong>Reliable:</strong> Message is retried or queued until successful delivery</li>
     * </ul>
     * 
     * <p>Error handling strategies may include:</p>
     * <ul>
     *   <li>Throwing exceptions for immediate failures</li>
     *   <li>Logging errors and continuing (fire-and-forget)</li>
     *   <li>Buffering messages for later retry</li>
     *   <li>Invoking error callbacks or handlers</li>
     * </ul>
     *
     * @param message the message to be sent; may be null depending on implementation
     * @throws MessageDeliveryException if the message cannot be delivered
     * @throws IllegalArgumentException if the message format is invalid
     * @throws IllegalStateException if the sender is not in a valid state for sending
     */
    void send(M message)

}
