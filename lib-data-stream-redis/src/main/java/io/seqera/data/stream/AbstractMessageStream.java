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

package io.seqera.data.stream;

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micronaut.core.annotation.Nullable;
import io.seqera.data.stream.metrics.NoopStreamMetrics;
import io.seqera.data.stream.metrics.Outcome;
import io.seqera.data.stream.metrics.StreamMetrics;
import io.seqera.serde.encode.StringEncodingStrategy;
import io.seqera.util.retry.ExponentialAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.seqera.data.stream.impl.SleepHelper.sleep;

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
public abstract class AbstractMessageStream<M> implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AbstractMessageStream.class);

    private static final AtomicInteger count = new AtomicInteger();

    /**
     * Granularity at which an in-loop pause re-checks {@link #closing}, so a cooperative
     * shutdown is not held up for a whole poll interval or backoff delay.
     */
    private static final long PAUSE_SLICE_MILLIS = 50;

    private final Map<String, MessageConsumer<M>> listeners = new ConcurrentHashMap<>();

    private final ExponentialAttempt attempt = new ExponentialAttempt();

    private final StringEncodingStrategy<M> encoder;

    private final MessageStream<String> stream;

    private final StreamMetrics metrics;

    private volatile Thread thread;

    /**
     * Set by {@link #awaitQuiescent(Duration)} to stop the dispatcher from claiming further
     * messages. The dispatcher observes it at the head of its loop and at every pause slice,
     * so it exits at a safe point rather than being interrupted mid-call.
     */
    private volatile boolean closing;

    private final String name0;

    /**
     * Constructs a new stream without metrics instrumentation. Behavior is identical
     * to passing {@link NoopStreamMetrics#INSTANCE} to {@link #AbstractMessageStream(MessageStream, StreamMetrics)}.
     */
    protected AbstractMessageStream(MessageStream<String> target) {
        this(target, NoopStreamMetrics.INSTANCE);
    }

    /**
     * Constructs a new stream, optionally instrumented through a {@link StreamMetrics} handle.
     *
     * <p>To publish Micrometer metrics, pass an instance of
     * {@code io.seqera.data.stream.metrics.MicrometerStreamMetrics}. To opt out,
     * pass {@link NoopStreamMetrics#INSTANCE} (or {@code null}, which is treated as no-op).
     *
     * <p>This class never references {@code io.micrometer.core.instrument.MeterRegistry}
     * directly, so it is loadable on classpaths without {@code micrometer-core}.
     *
     * @param target  the underlying {@link MessageStream} implementation
     * @param metrics the {@link StreamMetrics} handle, or {@code null} for no-op
     */
    protected AbstractMessageStream(MessageStream<String> target, @Nullable StreamMetrics metrics) {
        this.encoder = createEncodingStrategy();
        this.stream = target;
        this.metrics = (metrics != null) ? metrics : NoopStreamMetrics.INSTANCE;
        this.name0 = name() + "-thread-" + count.getAndIncrement();
    }

    protected abstract StringEncodingStrategy<M> createEncodingStrategy();

    protected Thread createListenerThread() {
        Thread thread = new Thread(() -> processMessages(), name0);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * @return The name of the message queue implementation
     */
    protected abstract String name();

    /**
     * @return
     *      The time interval to await before trying to read again the stream
     *      when no more entries are available.
     */
    protected abstract Duration pollInterval();

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
    public void offer(String streamId, M message) {
        final var msg = encoder.encode(message);
        stream.offer(streamId, msg);
    }

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
    public void addConsumer(String streamId, MessageConsumer<M> consumer) {
        // the use of synchronized block is meant to prevent a race condition while
        // updating the 'listeners' from concurrent invocations.
        // however, considering the addConsumer is invoked during the initialization phase
        // (and therefore in the same thread) in should not be really needed.
        synchronized (listeners) {
            if (listeners.containsKey(streamId)) {
                throw new IllegalStateException("Only one consumer can be defined for each stream - offending streamId=" + streamId + "; consumer=" + consumer);
            }
            // initialize the stream
            stream.init(streamId);
            // then add the consumer to the listeners
            listeners.put(streamId, consumer);
            // bind the backlog gauge for this stream id (no-op when metrics disabled)
            metrics.bindBacklog(streamId, () -> stream.length(streamId));
            // finally start the listener thread
            if (thread == null) {
                thread = createListenerThread();
            }
        }
    }

    /**
     * Deserialize the message as string into the target message object and process it by applying
     * the given consumer {@link MessageConsumer}.
     *
     * @param msg
     *      The message serialised as a string value
     * @param consumer
     *      The consumer {@link MessageConsumer} that will handle the message as a object
     * @param count
     *      An {@link AtomicInteger} counter incremented by one when this method is invoked,
     *      irrespective if the consumer is successful or not.
     * @return
     *      The result of the consumer {@link MessageConsumer} operation.
     */
    protected boolean processMessage(String msg, MessageConsumer<M> consumer, AtomicInteger count) {
        count.incrementAndGet();
        final M decoded = encoder.decode(msg);
        log.trace("Message stream - receiving message={}; decoded={}", msg, decoded);
        return consumer.accept(decoded);
    }

    /**
     * Run one consume cycle for the given stream and record the outcome on the
     * {@link StreamMetrics} handle. The outcome is derived from the {@code count}
     * delta (was the consumer lambda invoked?) and the return value of
     * {@link MessageStream#consume}.
     */
    private void consumeOne(String streamId, MessageConsumer<M> consumer, AtomicInteger count) {
        final long sample = metrics.startSample();
        final int countBefore = count.get();
        Outcome outcome = Outcome.EMPTY;
        try {
            final boolean accepted = stream.consume(streamId, (String msg) -> processMessage(msg, consumer, count));
            if (count.get() != countBefore) {
                outcome = accepted ? Outcome.PROCESSED : Outcome.ACTIVE;
            }
        }
        catch (Throwable t) {
            outcome = Outcome.ERRORED;
            throw t;
        }
        finally {
            metrics.recordOutcome(sample, streamId, outcome);
        }
    }

    /**
     * Process the messages as they are available from the underlying stream
     */
    protected void processMessages() {
        log.trace("Message stream - starting listener thread");
        // `closing` is checked first so a cooperative shutdown claims no further message; the
        // cycle already in progress below always runs to completion, which is what lets a
        // consumer finish its work (and its database writes) before the context tears down.
        while (!closing && !Thread.currentThread().isInterrupted()) {
            try {
                final var count = new AtomicInteger();
                for (Map.Entry<String, MessageConsumer<M>> entry : listeners.entrySet()) {
                    final var streamId = entry.getKey();
                    final var consumer = entry.getValue();
                    consumeOne(streamId, consumer, count);
                }
                // reset the attempt count because no error has been thrown
                attempt.reset();
                // if no message was sent, sleep for a while before retrying
                if (count.get() == 0) {
                    log.trace("Message stream - await before checking for new messages");
                    pause(pollInterval().toMillis());
                }
            }
            catch (Throwable e) {
                // A forced stop (close() fallback) surfaces as an interrupt, possibly wrapped by
                // the underlying client. Treat it as "exit now", not as a stream error to retry:
                // logging it at ERROR with a backoff would turn every hard shutdown into noise.
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    log.debug("Message stream {} interrupted - exiting listener thread", name0);
                    Thread.currentThread().interrupt();
                    break;
                }
                final var d0 = attempt.delay();
                log.error("Unexpected error on message stream {} (await: {}) - cause: {}", name0, d0, e.getMessage(), e);
                pause(d0.toMillis());
            }
        }
        log.trace("Message stream - exiting listener thread");
    }

    /**
     * Sleep up to {@code millis}, returning early once {@link #closing} is set or the thread is
     * interrupted. Used instead of a single long sleep so neither the poll interval nor an
     * exponential backoff delay can hold up a cooperative shutdown.
     */
    private void pause(long millis) {
        final long deadline = System.currentTimeMillis() + millis;
        long remaining;
        while (!closing
                && !Thread.currentThread().isInterrupted()
                && (remaining = deadline - System.currentTimeMillis()) > 0) {
            sleep(Math.min(PAUSE_SLICE_MILLIS, remaining));
        }
    }

    /**
     * Stop claiming new messages and wait for the dispatcher to finish the cycle it is running.
     *
     * <p>This is the cooperative half of {@link #close()}, exposed separately so a caller can
     * drain the stream while its collaborators — a database connection pool, for instance — are
     * still usable, and only then release resources.
     *
     * <p>Safe to call more than once, and safe to call before any consumer was registered.
     *
     * @param timeout
     *      how long to wait for the dispatcher to exit
     * @return
     *      {@code true} if the dispatcher stopped within the timeout, {@code false} if it is
     *      still running, in which case the caller decides whether to force a stop
     */
    public boolean awaitQuiescent(Duration timeout) {
        closing = true;
        final Thread t = thread;
        if (t == null) {
            return true;
        }
        try {
            t.join(Math.max(1, timeout.toMillis()));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /**
     * Shutdown orderly the stream.
     *
     * <p>Cooperative first: {@link #awaitQuiescent(Duration)} lets the dispatcher finish the
     * message it is holding and leave the loop at a safe point. Interrupting a thread parked in a
     * Redis read can hand a RESP-desynced connection back to the pool (libseqera#92), so the
     * interrupt below is a fallback for a dispatcher that overran {@link #closeTimeout()}, not the
     * normal path.
     *
     * <p>Callers that need the drain to complete while other beans are still alive should call
     * {@link #awaitQuiescent(Duration)} themselves, ahead of this method.
     */
    @Override
    public void close() {
        if (thread == null) {
            return;
        }
        if (awaitQuiescent(closeTimeout())) {
            return;
        }
        log.warn("Message stream {} did not stop within {} - forcing interrupt", name0, closeTimeout());
        thread.interrupt();
        try {
            thread.join(1_000);
        }
        catch (Exception e) {
            log.debug("Unexpected error while terminating {} - cause: {}", name0, e.getMessage());
        }
    }

    /**
     * How long {@link #close()} waits for a cooperative stop before interrupting the dispatcher.
     * Subclasses may override to align with an application-level shutdown budget.
     *
     * @return the cooperative close timeout, {@code 10s} by default
     */
    protected Duration closeTimeout() {
        return Duration.ofSeconds(10);
    }

    public int length(String streamId) {
        return stream.length(streamId);
    }

}
