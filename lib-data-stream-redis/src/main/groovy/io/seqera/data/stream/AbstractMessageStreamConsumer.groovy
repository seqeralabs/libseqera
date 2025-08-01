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
abstract class AbstractMessageStreamConsumer<M> implements Closeable {

    static final private AtomicInteger count = new AtomicInteger()

    final private Map<String,MessageConsumer<M>> listeners = new ConcurrentHashMap<>()
    
    final private ExponentialAttempt attempt = new ExponentialAttempt()

    final private StringEncodingStrategy<M> encoder

    final private MessageStream<String> stream

    private Thread thread

    private String name0

    AbstractMessageStreamConsumer(MessageStream<String> target) {
        this.encoder = createEncodingStrategy()
        this.stream = target
        this.name0 = name() + '-thread-' + count.getAndIncrement()
    }

    abstract protected StringEncodingStrategy<M> createEncodingStrategy()

    protected Thread createListenerThread() {
        final thread = new Thread(()-> processMessages(), name0)
        thread.setDaemon(true)
        thread.start()
        return thread
    }

    /**
     * @return The name of the message queue implementation
     */
    protected abstract String name()

    /**
     * @return
     *      The time interval to await before trying to read again the stream
     *      when no more entries are available.
     */
    protected abstract Duration pollInterval()

    /**
     * Registers a consumer to process messages from the specified stream.
     * 
     * <p>Only one consumer can be registered per stream ID. The consumer will be invoked
     * asynchronously by a background thread whenever messages are available in the stream.
     * The stream will be automatically initialized if this is the first consumer registration.</p>
     * 
     * <p>The background processing thread is started automatically when the first consumer
     * is registered and will continue running until the stream is closed.</p>
     * 
     * <p>Consumer requirements:</p>
     * <ul>
     *   <li>Must be thread-safe as it may be called from a background thread</li>
     *   <li>Should return {@code true} to acknowledge successful message processing</li>
     *   <li>Should return {@code false} if message processing fails or should be retried</li>
     *   <li>Should handle exceptions gracefully to avoid disrupting stream processing</li>
     * </ul>
     *
     * @param streamId the unique identifier of the stream to consume from; must not be null or empty
     * @param consumer the message consumer that will process messages; must not be null
     * @see MessageConsumer#accept(Object)
     */
    void addConsumer(String streamId, MessageConsumer<M> consumer) {
        // the use of synchronized block is meant to prevent a race condition while
        // updating the 'listeners' from concurrent invocations.
        // however, considering the addConsumer is invoked during the initialization phase
        // (and therefore in the same thread) in should not be really needed.
        synchronized (listeners) {
            if( listeners.containsKey(streamId))
                throw new IllegalStateException("Only one consumer can be defined for each stream - offending streamId=$streamId; consumer=$consumer")
            // initialize the stream
            stream.init(streamId)
            // then add the consumer to the listeners
            listeners.put(streamId, consumer)
            // finally start the listener thread
            if( !thread )
                thread = createListenerThread()
        }
    }

    /**
     * Deserialize the message as string into the target message object and process it by applying
     * the given consumer {@link Predicate<M>}.
     *
     * @param msg
     *      The message serialised as a string value
     * @param consumer
     *      The consumer {@link Predicate<M>} that will handle the message as a object
     * @param count
     *      An {@link AtomicInteger} counter incremented by one when this method is invoked,
     *      irrespective if the consumer is successful or not.
     * @return
     *      The result of the consumer {@link Predicate<M>} operation.
     */
    protected boolean processMessage(String msg, MessageConsumer<M> consumer, AtomicInteger count) {
        count.incrementAndGet()
        final decoded = encoder.decode(msg)
        log.trace "Message stream - receiving message=$msg; decoded=$decoded"
        return consumer.accept(decoded)
    }

    /**
     * Process the messages as they are available from the underlying stream
     */
    protected void processMessages() {
        log.trace "Message stream - starting listener thread"
        while( !Thread.currentThread().isInterrupted() ) {
            try {
                final count=new AtomicInteger()
                for( Map.Entry<String,MessageConsumer<M>> entry : listeners.entrySet() ) {
                    final streamId = entry.key
                    final consumer = entry.value
                    stream.consume(streamId, (String msg)-> processMessage(msg, consumer, count))
                }
                // reset the attempt count because no error has been thrown
                attempt.reset()
                // if no message was sent, sleep for a while before retrying
                if( count.get()==0 ) {
                    log.trace "Message stream - await before checking for new messages"
                    Thread.sleep(pollInterval().toMillis())
                }
            }
            catch (InterruptedException e) {
                log.debug "Message streaming interrupt exception - cause: ${e.message}"
                Thread.currentThread().interrupt()
                break
            }
            catch (Throwable e) {
                final d0 = attempt.delay()
                log.error("Unexpected error on message stream ${name0} (await: ${d0}) - cause: ${e.message}", e)
                Thread.sleep(d0.toMillis())
            }
        }
        log.trace "Message stream - exiting listener thread"
    }

    /**
     * Shutdown orderly the stream
     */
    @Override
    void close() {
        if( !thread )
            return
        // interrupt the thread
        thread.interrupt()
        // wait for the termination
        try {
            thread.join(1_000)
        }
        catch (Exception e) {
            log.debug "Unexpected error while terminating ${name0} - cause: ${e.message}"
        }
    }

    int length(String streamId) {
        stream.length(streamId)
    }

}
