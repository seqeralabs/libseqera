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

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.micronaut.core.annotation.Nullable;
import io.seqera.data.workqueue.metrics.NoopQueueMetrics;
import io.seqera.data.workqueue.metrics.Outcome;
import io.seqera.data.workqueue.metrics.QueueMetrics;
import io.seqera.serde.encode.StringEncodingStrategy;
import io.seqera.util.retry.ExponentialAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.seqera.data.workqueue.SleepHelper.sleep;

/**
 * Abstract base implementation of a work queue that provides asynchronous message consumption.
 *
 * <p>This class implements the core functionality for a work queue that continuously consumes
 * messages from underlying queues and delivers them to registered consumers. It provides:</p>
 *
 * <ul>
 *   <li><strong>Asynchronous Processing:</strong> Uses a background thread to continuously poll for messages</li>
 *   <li><strong>Consumer Management:</strong> Manages registration of message consumers for different queues</li>
 *   <li><strong>Error Resilience:</strong> Implements exponential backoff for error recovery</li>
 *   <li><strong>Message Serialization:</strong> Handles encoding/decoding of messages transparently</li>
 *   <li><strong>Resource Management:</strong> Proper cleanup and shutdown of background resources</li>
 * </ul>
 *
 * <p>The implementation follows a reactor pattern where:</p>
 * <ol>
 *   <li>Consumers register their interest in specific queues</li>
 *   <li>A background thread continuously polls all registered queues</li>
 *   <li>Messages are deserialized and delivered to appropriate consumers</li>
 *   <li>Consumer acknowledgments control message processing flow</li>
 * </ol>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Subclass implementation
 * public class MyWorkQueue extends AbstractWorkQueue<MyEvent> {
 *     protected StringEncodingStrategy<MyEvent> createEncodingStrategy() {
 *         return new JsonEncodingStrategy<>() {};
 *     }
 *
 *     protected String name() { return "my-events"; }
 *     protected Duration pollInterval() { return Duration.ofSeconds(1); }
 * }
 *
 * // Usage
 * MyWorkQueue queue = new MyWorkQueue(underlyingQueue);
 *
 * // Add consumer for a specific queue
 * queue.addConsumer("user-events", (event, lease) -> {
 *     processUserEvent(event);
 *     return MessageConsumer.Decision.ACK; // Acknowledge successful processing
 * });
 *
 * // Send messages (will be processed asynchronously by registered consumers)
 * queue.offer("user-events", new UserLoginEvent(userId));
 * }</pre>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li><strong>Single Consumer per Queue:</strong> Each queue can have only one registered consumer</li>
 *   <li><strong>Automatic Thread Management:</strong> Background thread is started when first consumer is added</li>
 *   <li><strong>Graceful Shutdown:</strong> Implements {@link Closeable} for proper resource cleanup</li>
 *   <li><strong>Error Recovery:</strong> Uses exponential backoff to handle transient failures</li>
 * </ul>
 *
 * @param <M> the type of messages that can be processed by this queue
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see WorkQueue
 * @see MessageConsumer
 */
public abstract class AbstractWorkQueue<M> implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorkQueue.class);

    private static final AtomicInteger count = new AtomicInteger();

    /**
     * Granularity at which an in-loop pause re-checks {@link #closing}, so a cooperative
     * shutdown is not held up for a whole poll interval or backoff delay.
     */
    private static final long PAUSE_SLICE_MILLIS = 50;

    private final Map<String, MessageConsumer<M>> listeners = new ConcurrentHashMap<>();

    private final ExponentialAttempt attempt = new ExponentialAttempt();

    private final StringEncodingStrategy<M> encoder;

    private final WorkQueue<String> queue;

    private final QueueMetrics metrics;

    private volatile Thread thread;

    /**
     * Set by {@link #awaitQuiescent(Duration)} to stop the dispatcher from claiming further
     * messages. The dispatcher observes it at the head of its loop and at every pause slice,
     * so it exits at a safe point rather than being interrupted mid-call.
     */
    private volatile boolean closing;

    /**
     * Set once {@link #close(Duration)} has run its cooperative wait. A second close — the
     * {@code @PreDestroy} backstop after an explicit drain, for instance — must not wait again:
     * the first call either saw the dispatcher stop or already decided to give up, and repeating
     * the wait during bean destruction spends a shutdown budget that was spent once already.
     */
    private volatile boolean closeAttempted;

    private final String name0;

    /**
     * Constructs a new queue without metrics instrumentation. Behavior is identical
     * to passing {@link NoopQueueMetrics#INSTANCE} to {@link #AbstractWorkQueue(WorkQueue, QueueMetrics)}.
     */
    protected AbstractWorkQueue(WorkQueue<String> target) {
        this(target, NoopQueueMetrics.INSTANCE);
    }

    /**
     * Constructs a new queue, optionally instrumented through a {@link QueueMetrics} handle.
     *
     * <p>To publish Micrometer metrics, pass an instance of
     * {@code io.seqera.data.workqueue.metrics.MicrometerQueueMetrics}. To opt out,
     * pass {@link NoopQueueMetrics#INSTANCE} (or {@code null}, which is treated as no-op).
     *
     * <p>This class never references {@code io.micrometer.core.instrument.MeterRegistry}
     * directly, so it is loadable on classpaths without {@code micrometer-core}.
     *
     * @param target  the underlying {@link WorkQueue} implementation
     * @param metrics the {@link QueueMetrics} handle, or {@code null} for no-op
     */
    protected AbstractWorkQueue(WorkQueue<String> target, @Nullable QueueMetrics metrics) {
        this.encoder = createEncodingStrategy();
        this.queue = target;
        this.metrics = (metrics != null) ? metrics : NoopQueueMetrics.INSTANCE;
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
     *      The time interval to await before trying to read again the queue
     *      when no more entries are available.
     */
    protected abstract Duration pollInterval();

    /**
     * Adds a message to the specified queue for asynchronous processing.
     *
     * <p>The message will be serialized using the configured encoding strategy and added
     * to the underlying queue. If a consumer is registered for the specified queue ID,
     * the message will be processed asynchronously by the background thread.</p>
     *
     * <p>This method is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param queueId the unique identifier of the target queue; must not be null or empty
     * @param message the message to be added to the queue; may be null depending on encoding strategy
     */
    public void offer(String queueId, M message) {
        final var msg = encoder.encode(message);
        queue.offer(queueId, msg);
    }

    /**
     * Registers a consumer to process messages from the specified queue.
     *
     * <p>Only one consumer can be registered per queue ID. The consumer will be invoked
     * asynchronously by a background thread whenever messages are available in the queue.
     * The queue will be automatically initialized if this is the first consumer registration.</p>
     *
     * <p>The background processing thread is started automatically when the first consumer
     * is registered and will continue running until the queue is closed.</p>
     *
     * <p>Consumer requirements:</p>
     * <ul>
     *   <li>Must be thread-safe as it may be called from a background thread</li>
     *   <li>Should return {@link MessageConsumer.Decision#ACK} to acknowledge successful processing</li>
     *   <li>Should return {@link MessageConsumer.Decision#RETRY} if processing should be retried</li>
     *   <li>Should return {@link MessageConsumer.Decision#DEFERRED} when a task takes the
     *       message lease and settles it later via {@link MessageLease}</li>
     *   <li>May override {@link MessageConsumer#ready()} to gate admission — the dispatcher
     *       skips the queue while the consumer reports not ready</li>
     * </ul>
     *
     * @param queueId the unique identifier of the queue to consume from; must not be null or empty
     * @param consumer the message consumer that will process messages; must not be null
     * @see MessageConsumer#accept(Object, MessageLease)
     */
    public void addConsumer(String queueId, MessageConsumer<M> consumer) {
        // the use of synchronized block is meant to prevent a race condition while
        // updating the 'listeners' from concurrent invocations.
        // however, considering the addConsumer is invoked during the initialization phase
        // (and therefore in the same thread) in should not be really needed.
        synchronized (listeners) {
            if (listeners.containsKey(queueId)) {
                throw new IllegalStateException("Only one consumer can be defined for each queue - offending queueId=" + queueId + "; consumer=" + consumer);
            }
            // initialize the queue
            queue.init(queueId);
            // then add the consumer to the listeners
            listeners.put(queueId, consumer);
            // bind the backlog gauge for this queue id (no-op when metrics disabled)
            metrics.bindBacklog(queueId, () -> queue.length(queueId));
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
     * @param lease
     *      The {@link MessageLease} settlement handle for this delivery
     * @param consumer
     *      The consumer {@link MessageConsumer} that will handle the message as a object
     * @param count
     *      An {@link AtomicInteger} counter incremented by one when this method is invoked,
     *      irrespective if the consumer is successful or not.
     * @return
     *      The {@link MessageConsumer.Decision} of the consumer operation.
     */
    protected MessageConsumer.Decision processMessage(String msg, MessageLease lease, MessageConsumer<M> consumer, AtomicInteger count) {
        count.incrementAndGet();
        final M decoded = encoder.decode(msg);
        log.trace("Work queue - receiving message={}; decoded={}", msg, decoded);
        return consumer.accept(decoded, lease);
    }

    /**
     * Run one consume cycle for the given queue and record the outcome on the
     * {@link QueueMetrics} handle. The outcome is derived from the {@code count}
     * delta (was the consumer lambda invoked?) and the {@link MessageConsumer.Decision}
     * returned by {@link WorkQueue#consume}: {@code ACK} records processed,
     * {@code RETRY} records active, and {@code DEFERRED} records active plus a
     * distinct deferred counter (the task-settled outcome is not timed here).
     */
    private MessageConsumer.Decision consumeOne(String queueId, MessageConsumer<M> consumer, AtomicInteger count) {
        final long sample = metrics.startSample();
        final int countBefore = count.get();
        Outcome outcome = Outcome.EMPTY;
        try {
            final MessageConsumer.Decision decision = queue.consume(queueId,
                    (String msg, MessageLease lease) -> processMessage(msg, lease, consumer, count));
            if (count.get() != countBefore) {
                outcome = decision == MessageConsumer.Decision.ACK ? Outcome.PROCESSED : Outcome.ACTIVE;
                if (decision == MessageConsumer.Decision.DEFERRED) {
                    metrics.deferred(queueId);
                }
            }
            return decision;
        }
        catch (Throwable t) {
            outcome = Outcome.ERRORED;
            throw t;
        }
        finally {
            metrics.recordOutcome(sample, queueId, outcome);
        }
    }

    /**
     * Process the messages as they are available from the underlying queue
     */
    protected void processMessages() {
        log.trace("Work queue - starting listener thread");
        // `closing` is checked first so a cooperative shutdown claims no further message; the
        // cycle already in progress below always runs to completion, which is what lets a
        // consumer finish its work (and its database writes) before the context tears down.
        while (!closing && !Thread.currentThread().isInterrupted()) {
            try {
                final var count = new AtomicInteger();
                boolean progressed = false;
                for (Map.Entry<String, MessageConsumer<M>> entry : listeners.entrySet()) {
                    final var queueId = entry.getKey();
                    final var consumer = entry.getValue();
                    // admission gate: do not claim from a queue whose consumer is not
                    // ready — counts as no-message, so an idle loop still pauses below
                    if (!consumer.ready()) {
                        metrics.saturated(queueId);
                        continue;
                    }
                    final MessageConsumer.Decision decision = consumeOne(queueId, consumer, count);
                    // only ACK and DEFERRED are progress: a RETRY must NOT keep the loop
                    // hot — a consumer retrying fast (e.g. against the local queue, which
                    // has no claim clock) would otherwise redeliver at loop speed
                    progressed |= decision == MessageConsumer.Decision.ACK
                            || decision == MessageConsumer.Decision.DEFERRED;
                }
                // reset the attempt count because no error has been thrown
                attempt.reset();
                // pause unless a cycle made real progress, so idle AND retry-only cycles
                // are both paced by the poll interval
                if (!progressed) {
                    log.trace("Work queue - await before checking for new messages");
                    pause(pollInterval().toMillis());
                }
            }
            catch (Throwable e) {
                // A forced stop (close() fallback) surfaces as an interrupt, possibly wrapped by
                // the underlying client. Treat it as "exit now", not as a queue error to retry:
                // logging it at ERROR with a backoff would turn every hard shutdown into noise.
                if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                    log.debug("Work queue {} interrupted - exiting listener thread", name0);
                    Thread.currentThread().interrupt();
                    break;
                }
                final var d0 = attempt.delay();
                log.error("Unexpected error on work queue {} (await: {}) - cause: {}", name0, d0, e.getMessage(), e);
                pause(d0.toMillis());
            }
        }
        log.trace("Work queue - exiting listener thread");
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
     * drain the queue while its collaborators — a database connection pool, for instance — are
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
            log.info("Work queue {} interrupted while awaiting quiescence", name0, e);
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    /**
     * Shutdown orderly the queue.
     *
     * <p>Cooperative first: {@link #awaitQuiescent(Duration)} lets the dispatcher finish the
     * message it is holding and leave the loop at a safe point. Interrupting a thread parked in a
     * Redis read can hand a RESP-desynced connection back to the pool (libseqera#92), so the
     * dispatcher is never interrupted: the flag alone guarantees it exits, and it is a daemon.
     *
     * <p>Uses {@link #closeTimeout()} as the budget. Callers that have already spent part of an
     * overall shutdown budget should call {@link #close(Duration)} with what remains; callers that
     * need the drain to complete while other beans are still alive should call
     * {@link #awaitQuiescent(Duration)} themselves, ahead of either.
     */
    @Override
    public void close() {
        close(closeTimeout());
    }

    /**
     * Shutdown orderly the queue within an explicit budget.
     *
     * <p>Exists so a caller that has already spent part of an overall shutdown budget can pass what
     * remains, instead of this method starting a second, independent timer. A caller that drains
     * first and then closes with {@link #closeTimeout()} can otherwise overrun its own deadline —
     * and if that deadline came from a container's graceful-shutdown grace period, overrunning it
     * means being hard-stopped mid-drain, which is the opposite of what draining is for.
     *
     * <p>Only the first close waits. Repeated calls — an explicit drain followed by the
     * {@code @PreDestroy} backstop — return immediately, for the same budget reason.
     *
     * @param timeout how long to wait for a cooperative stop before interrupting the dispatcher
     */
    public void close(Duration timeout) {
        if (thread == null) {
            return;
        }
        if (closeAttempted) {
            return;
        }
        closeAttempted = true;
        if (awaitQuiescent(timeout)) {
            return;
        }
        // Stop waiting, but do not interrupt. The `closing` flag already guarantees the dispatcher
        // exits at its next loop-head check, and the thread is a daemon so it can never hold up JVM
        // exit — so an interrupt only shortens a wait we have already decided not to keep making.
        // Against that it is actively harmful: it can hand a RESP-desynced connection back to the
        // pool (libseqera#92), and it was observed to propagate into a handler still running on the
        // executor, cutting short the very work a drain exists to protect.
        log.warn("Work queue {} still running after {} - leaving it to exit on its own", name0, timeout);
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

    public int length(String queueId) {
        return queue.length(queueId);
    }

}
