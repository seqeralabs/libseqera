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
 * Abstract base implementation of a message stream producer that provides message publishing functionality.
 * 
 * <p>This class implements the core functionality for producing messages to streams. It provides:</p>
 * 
 * <ul>
 *   <li><strong>Message Serialization:</strong> Handles encoding of messages transparently using configurable encoding strategies</li>
 *   <li><strong>Stream Publishing:</strong> Provides a simple interface to offer messages to named streams</li>
 *   <li><strong>Type Safety:</strong> Generic type support ensures type-safe message handling</li>
 * </ul>
 * 
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Subclass implementation
 * public class MyMessageStreamProducer extends AbstractMessageStreamProducer<MyEvent> {
 *     protected StringEncodingStrategy<MyEvent> createEncodingStrategy() {
 *         return new JsonEncodingStrategy<>() {};
 *     }
 * }
 * 
 * // Usage
 * MyMessageStreamProducer producer = new MyMessageStreamProducer(underlyingStream);
 * 
 * // Send messages to streams
 * producer.offer("user-events", new UserLoginEvent(userId));
 * producer.offer("system-events", new SystemEvent("startup"));
 * }</pre>
 * 
 * <p>This class is designed to work in conjunction with {@link AbstractMessageStreamConsumer}
 * which handles the consumption side of message streaming.</p>
 *
 * @param <M> the type of messages that can be produced by this stream
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see MessageStream
 * @see AbstractMessageStreamConsumer
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
