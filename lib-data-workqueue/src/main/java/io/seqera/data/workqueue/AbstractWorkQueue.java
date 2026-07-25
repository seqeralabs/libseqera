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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
 * messages from an underlying queue and delivers them to registered consumers. It provides:</p>
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
 * // Supply the handler executor (mandatory, no default) before adding consumers
 * queue.withHandlerExecutor(executorService);
 *
 * // Add consumer for a specific queue
 * queue.addConsumer("user-events", event -> {
 *     processUserEvent(event);
 *     return true; // Acknowledge successful processing
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

    private final Map<String, MessageConsumer<M>> listeners = new ConcurrentHashMap<>();

    private final ExponentialAttempt attempt = new ExponentialAttempt();

    private final StringEncodingStrategy<M> encoder;

    private final WorkQueue<String> queue;

    private final QueueMetrics metrics;

    private volatile Thread thread;

    private final String name0;

    /**
     * One active handler invocation. It exists only while {@code accept()} is running,
     * never for the full lifecycle of a non-terminal message.
     */
    private record InFlight(String queueId, String leaseId, String message) {
        String key() {
            return queueId + '|' + leaseId;
        }
    }

    /**
     * Handler invocations currently running. Every entry is heartbeated until that one
     * invocation returns, allowing handler time to exceed the queue visibility timeout.
     */
    private final Map<String, InFlight> active = new ConcurrentHashMap<>();

    private final Map<String, Long> activeSince = new ConcurrentHashMap<>();

    private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Executor that runs the message handlers. Supplied by the consumer via
     * {@link #withHandlerExecutor} before the first {@link #addConsumer} — there is no default,
     * so {@link #startProcessing()} fails fast if it was never set. Micronaut consumers pass the
     * injected {@code BLOCKING} executor. Handler concurrency is bounded by {@link #slots}, not by
     * this executor, so it is never sized or shut down here.
     */
    private volatile ExecutorService pool;

    /**
     * Bounds handler invocations, not live messages. A permit is held only from delivery
     * until one {@code accept()} call returns.
     */
    private volatile Semaphore slots;

    /**
     * Renews every active invocation on a fixed cadence so a handler may safely run longer
     * than the visibility timeout.
     */
    private volatile ScheduledExecutorService heartbeat;

    private volatile boolean closed;

    private final AtomicInteger deliverySequence = new AtomicInteger();

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
     * @return The name of the work queue implementation
     */
    protected abstract String name();

    /**
     * @return
     *      The time interval to await before trying to read again the queue
     *      when no more entries are available. Redis retry cadence is governed
     *      by the visibility timeout of the pending Stream entry.
     */
    protected abstract Duration pollInterval();

    /**
     * @return
     *      The maximum number of handler invocations that may run concurrently on this
     *      instance. A non-terminal message releases its permit after each invocation.
     */
    protected int concurrency() {
        return 1;
    }

    /**
     * Number of new-message delivery opportunities for each expired-message reclaim.
     * Both paths fall back to the other when empty, so neither intake nor retry can starve.
     */
    protected int newToRetryRatio() {
        return 3;
    }

    /**
     * @return
     *      How often in-flight leases are renewed so an alive consumer keeps ownership
     *      of its message regardless of how long its handler runs. Must be shorter than
     *      the underlying queue's visibility timeout; subclasses backed by a configuration
     *      should wire this to {@code visibility-timeout / 3}.
     */
    protected Duration heartbeatInterval() {
        final Duration d = queue.heartbeatInterval();
        return d != null ? d : Duration.ofSeconds(20);
    }

    /**
     * @return
     *      Duration after which a still-running invocation is reported as stalled.
     *      This is an observability threshold only: the lease continues to be renewed,
     *      because releasing it while the handler runs would create overlapping execution.
     */
    protected Duration maxProcessingTime() {
        final Duration d = queue.maxProcessingTime();
        return d != null ? d : Duration.ofMinutes(15);
    }

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
     *   <li>Should return {@code true} to acknowledge successful message processing</li>
     *   <li>Should return {@code false} if message processing fails or should be retried</li>
     *   <li>Should handle exceptions gracefully to avoid disrupting queue processing</li>
     * </ul>
     *
     * @param queueId the unique identifier of the queue to consume from; must not be null or empty
     * @param consumer the message consumer that will process messages; must not be null
     * @see MessageConsumer#accept(Object)
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
            // finally start the dispatcher thread and its supporting executors
            if (thread == null) {
                startProcessing();
            }
        }
    }

    /**
     * Lazily create the heartbeat daemon and invocation-capacity gate, then start the
     * dispatcher thread. Invoked once, when the first
     * consumer is registered.
     */
    private void startProcessing() {
        // a handler executor must be supplied via withHandlerExecutor() before processing starts
        Objects.requireNonNull(pool, "Handler executor not set - call withHandlerExecutor() before addConsumer()");
        // slots bound active handler calls, never the number of live queue entries
        this.slots = new Semaphore(Math.max(1, concurrency()));
        this.heartbeat = new ScheduledThreadPoolExecutor(1, daemonFactory(name() + "-heartbeat-" + count.get()));
        final long hb = heartbeatInterval().toMillis();
        this.heartbeat.scheduleAtFixedRate(this::heartbeatTick, hb, hb, TimeUnit.MILLISECONDS);
        this.thread = createListenerThread();
    }

    /**
     * Supply the executor used to run message handlers. Consumers must call this
     * <strong>before</strong> the first {@link #addConsumer} — there is no default executor.
     * Micronaut-managed consumers pass the injected {@code @Named(TaskExecutors.BLOCKING)}
     * {@link ExecutorService}. The executor is never shut down by {@link #close()}
     * (it is shared / container-managed).
     *
     * @param executor the shared handler executor; must not be {@code null}
     */
    public void withHandlerExecutor(ExecutorService executor) {
        this.pool = Objects.requireNonNull(executor, "Handler executor cannot be null");
    }

    private static ThreadFactory daemonFactory(String prefix) {
        final AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            final Thread t = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Deserialize the message as string into the target message object and process it by applying
     * the given consumer {@link MessageConsumer}.
     *
     * @param msg
     *      The message serialised as a string value
     * @param consumer
     *      The consumer {@link MessageConsumer} that will handle the message as a object
     * @return
     *      The result of the consumer {@link MessageConsumer} operation.
     */
    protected boolean processMessage(String msg, MessageConsumer<M> consumer) {
        final M decoded = encoder.decode(msg);
        log.trace("Work queue - receiving message={}; decoded={}", msg, decoded);
        return consumer.accept(decoded);
    }

    /**
     * The dispatcher loop (runs on the listener thread). It never runs a handler itself:
     * for every queue that has free invocation capacity it selects fairly between new
     * messages and expired pending messages, then submits one handler call to the pool.
     */
    protected void processMessages() {
        log.trace("Work queue - starting dispatcher thread");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean polled = false;
                for (Map.Entry<String, MessageConsumer<M>> entry : listeners.entrySet()) {
                    // A permit is held for one handler invocation only.
                    if (!slots.tryAcquire()) {
                        break;
                    }
                    // dispatchOne releases the permit itself when nothing is polled
                    polled = dispatchOne(entry.getKey()) || polled;
                }
                // reset the attempt count because no error has been thrown
                attempt.reset();
                // if nothing was polled this cycle, sleep for a while before retrying
                if (!polled) {
                    log.trace("Work queue - await before checking for new messages");
                    Thread.sleep(pollInterval().toMillis());
                }
            }
            catch (InterruptedException e) {
                log.debug("Work queue interrupt exception - cause: {}", e.getMessage());
                Thread.currentThread().interrupt();
                break;
            }
            catch (Throwable e) {
                final var d0 = attempt.delay();
                log.error("Unexpected error on work queue {} (await: {}) - cause: {}", name0, d0, e.getMessage(), e);
                sleep(d0.toMillis());
            }
        }
        log.trace("Work queue - exiting dispatcher thread");
    }

    /**
     * Poll a single queue (an invocation permit has already been acquired by the caller)
     * and submit one delivery to the pool.
     * If nothing is available the permit is released and {@code false} is returned.
     *
     * @return {@code true} if a message was polled and submitted, {@code false} otherwise
     */
    private boolean dispatchOne(String queueId) {
        boolean submitted = false;
        try {
            final WorkQueue.Lease<String> lease = receiveFair(queueId);
            if (lease == null) {
                metrics.recordOutcome(metrics.startSample(), queueId, Outcome.EMPTY);
                return false;
            }
            final var e = new InFlight(queueId, lease.id(), lease.message());
            // Guard against a local self-reclaim after a delayed heartbeat.
            if (active.putIfAbsent(e.key(), e) != null) {
                return false;   // 'submitted' stays false → finally releases this permit
            }
            activeSince.put(e.key(), System.currentTimeMillis());
            if (!submitRun(e)) {
                activeSince.remove(e.key());
                active.remove(e.key());
                return false;
            }
            submitted = true;
            return true;
        }
        finally {
            // the permit is held only once the lease is in flight; release it on an empty
            // poll or an exception so the single acquire in the dispatcher stays balanced
            if (!submitted) {
                slots.release();
            }
        }
    }

    private WorkQueue.Lease<String> receiveFair(String queueId) {
        final int ratio = Math.max(1, newToRetryRatio());
        final boolean preferNew = Math.floorMod(deliverySequence.getAndIncrement(), ratio + 1) < ratio;
        WorkQueue.Lease<String> result = preferNew
                ? queue.receiveNew(queueId)
                : queue.reclaim(queueId);
        if (result == null) {
            result = preferNew
                    ? queue.reclaim(queueId)
                    : queue.receiveNew(queueId);
        }
        return result;
    }

    /**
     * Submit the processing of an in-flight lease to the worker pool. Swallows the
     * rejection that occurs when the pool is being shut down.
     */
    private boolean submitRun(InFlight e) {
        try {
            pool.execute(() -> run(e));
            return true;
        }
        catch (RejectedExecutionException ex) {
            log.debug("Work queue - worker pool rejected task for entry={} (shutting down)", e.key());
            return false;
        }
    }

    /**
     * Runs exactly one {@code accept()} invocation. A terminal result is acknowledged;
     * a non-terminal result is touched once and left in the PEL for reclaim after the
     * visibility timeout. Either outcome releases the invocation permit.
     */
    private void run(InFlight e) {
        try {
            final boolean accepted = invokeHandler(e);
            if (accepted) {
                acknowledge(e);
            }
            else if (!closed) {
                retryLater(e);
            }
        }
        finally {
            finishAttempt(e);
        }
    }

    /**
     * Run one {@code accept()} invocation on the worker thread, recording the metrics
     * outcome. Returns {@code true} for a terminal result, {@code false} for
     * not-yet-terminal or an error (both keep the lease for a later re-poll).
     */
    private boolean invokeHandler(InFlight e) {
        final MessageConsumer<M> consumer = listeners.get(e.queueId());
        final long sample = metrics.startSample();
        boolean accepted = false;
        Outcome outcome = Outcome.ACTIVE;
        try {
            accepted = processMessage(e.message(), consumer);
            outcome = accepted ? Outcome.PROCESSED : Outcome.ACTIVE;
        }
        catch (Throwable t) {
            outcome = Outcome.ERRORED;
            log.error("Work queue - error processing entry={} - cause: {}", e.key(), t.getMessage(), t);
        }
        finally {
            metrics.recordOutcome(sample, e.queueId(), outcome);
        }
        return accepted;
    }

    /** Terminal result: acknowledge the message and release its lease. */
    private void acknowledge(InFlight e) {
        try {
            queue.ack(e.queueId(), e.leaseId());
        }
        catch (Throwable t) {
            log.error("Work queue - error acking entry={} - cause: {}", e.key(), t.getMessage(), t);
        }
    }

    /**
     * Reset the delivery idle time after a non-terminal result, then leave the entry in
     * the Stream PEL. It becomes eligible for another invocation after visibility timeout.
     */
    private void retryLater(InFlight e) {
        try {
            queue.release(e.queueId(), e.leaseId());
        }
        catch (Throwable t) {
            log.warn("Work queue - error touching retry entry={} - cause: {}", e.key(), t.getMessage());
        }
    }

    private void finishAttempt(InFlight e) {
        final String key = e.key();
        activeSince.remove(key);
        warned.remove(key);
        if (active.remove(key) != null) {
            slots.release();
        }
        if (closed && active.isEmpty() && heartbeat != null) {
            heartbeat.shutdown();
        }
    }

    /**
     * Heartbeat every currently executing invocation. Long-running handlers are warned
     * about, but never evicted while their thread remains active.
     */
    private void heartbeatTick() {
        final long now = System.currentTimeMillis();
        final long maxMillis = maxProcessingTime().toMillis();
        for (InFlight e : active.values()) {
            final String key = e.key();
            final long start = activeSince.getOrDefault(key, now);
            if (now - start > maxMillis) {
                if (warned.add(key)) {
                    log.warn("Work queue - handler still active for entry={} after {}; continuing lease renewal to prevent overlap",
                            key, Duration.ofMillis(now - start));
                }
            }
            try {
                queue.renewLease(e.queueId(), e.leaseId());
            }
            catch (Throwable t) {
                // swallow transient errors; the next tick retries
                log.warn("Work queue - error renewing lease for entry={} - cause: {}", key, t.getMessage());
            }
        }
    }

    /**
     * Stop intake. Active handlers remain heartbeated until they finish; close never makes
     * an entry reclaimable while its handler thread is still executing.
     */
    @Override
    public void close() {
        if (thread == null) {
            return;
        }
        closed = true;
        // 1. stop the dispatcher
        thread.interrupt();
        try {
            thread.join(1_000);
        }
        catch (Exception e) {
            log.debug("Unexpected error while terminating {} - cause: {}", name0, e.getMessage());
        }
        // The handler executor is shared/container-managed. Keep the heartbeat daemon alive
        // until every active invocation completes; finishAttempt shuts it down at that point.
        if (heartbeat != null && active.isEmpty()) {
            heartbeat.shutdown();
        }
    }

    public int length(String queueId) {
        return queue.length(queueId);
    }

}
