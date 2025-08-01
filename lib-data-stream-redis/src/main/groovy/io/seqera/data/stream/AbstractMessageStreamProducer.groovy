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

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.serde.encode.StringEncodingStrategy
import io.seqera.util.retry.ExponentialAttempt
/**
 * Abstract base implementation of a message stream that provides asynchronous message consumption.
 * 
 * <p>This class implements the core functionality for a message stream that continuously consumes
 * messages from underlying streams and delivers them to registered consumers. It provides:</p>
 * 
 * <ul>
 *   <li><strong>Asynchronous Processing:</strong> Uses a background thread to continuously poll for messages</li>
 *   <li><strong>Consumer Management:</strong> Manages registration of message consumers for different streams</li>
 *   <li><strong>Error Resilience:</strong> Implements exponential backoff for error recovery</li>
 *   <li><strong>Message Serialization:</strong> Handles encoding/decoding of messages transparently</li>
 *   <li><strong>Resource Management:</strong> Proper cleanup and shutdown of background resources</li>
 * </ul>
 * 
 * <p>The implementation follows a reactor pattern where:</p>
 * <ol>
 *   <li>Consumers register their interest in specific streams</li>
 *   <li>A background thread continuously polls all registered streams</li>
 *   <li>Messages are deserialized and delivered to appropriate consumers</li>
 *   <li>Consumer acknowledgments control message processing flow</li>
 * </ol>
 * 
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Subclass implementation
 * public class MyMessageStream extends AbstractMessageStream<MyEvent> {
 *     protected StringEncodingStrategy<MyEvent> createEncodingStrategy() {
 *         return new JsonEncodingStrategy<>() {};
 *     }
 *     
 *     protected String name() { return "my-events"; }
 *     protected Duration pollInterval() { return Duration.ofSeconds(1); }
 * }
 * 
 * // Usage
 * MyMessageStream stream = new MyMessageStream(underlyingStream);
 * 
 * // Add consumer for a specific stream
 * stream.addConsumer("user-events", event -> {
 *     processUserEvent(event);
 *     return true; // Acknowledge successful processing
 * });
 * 
 * // Send messages (will be processed asynchronously by registered consumers)
 * stream.offer("user-events", new UserLoginEvent(userId));
 * }</pre>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li><strong>Single Consumer per Stream:</strong> Each stream can have only one registered consumer</li>
 *   <li><strong>Automatic Thread Management:</strong> Background thread is started when first consumer is added</li>
 *   <li><strong>Graceful Shutdown:</strong> Implements {@link Closeable} for proper resource cleanup</li>
 *   <li><strong>Error Recovery:</strong> Uses exponential backoff to handle transient failures</li>
 * </ul>
 *
 * @param <M> the type of messages that can be processed by this stream
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see MessageStream
 * @see MessageConsumer
 */
@Slf4j
@CompileStatic
abstract class AbstractMessageStreamProducer<M> {

    final private StringEncodingStrategy<M> encoder

    final private MessageStream<String> stream

    AbstractMessageStreamProducer(MessageStream<String> target) {
        this.encoder = createEncodingStrategy()
        this.stream = target
    }

    abstract protected StringEncodingStrategy<M> createEncodingStrategy()

    /**
     * Adds a message to the specified stream for asynchronous processing.
     * 
     * <p>The message will be serialized using the configured encoding strategy and added
     * to the underlying stream. If a consumer is registered for the specified stream ID,
     * the message will be processed asynchronously by the background thread.</p>
     * 
     * <p>This method is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param streamId the unique identifier of the target stream; must not be null or empty
     * @param message the message to be added to the stream; may be null depending on encoding strategy
     */
    void offer(String streamId, M message) {
        final msg = encoder.encode(message)
        stream.offer(streamId, msg)
    }

}
