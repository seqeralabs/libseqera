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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * A message picked up from a queue and held while it is processed. The
     * {@code queueId} + {@code leaseId} pair identifies the delivered entry; the
     * {@code message} is kept so a not-yet-terminal command can be re-invoked in-process
     * (Model B) without re-reading it from the queue.
     */
    private record InFlight(String queueId, String leaseId, String message) {
        String key() {
            return queueId + '|' + leaseId;
        }
    }

    /**
     * Leases held from pickup to terminal/crash; every entry is heartbeated by the
     * daemon so an alive consumer is never reclaimed. Keyed by {@code queueId|leaseId}.
     */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    /**
     * Subset of {@link #inFlight} whose {@code accept()} invocation is running right now,
     * mapped to the wall-clock millis at which that invocation started. Used by the
     * heartbeat daemon to enforce {@code max-processing-time} on a single invocation.
     */
    private final Map<String, Long> active = new ConcurrentHashMap<>();

    /** Shared virtual-thread executor used when no handler executor is supplied. */
    private static final ExecutorService DEFAULT_WORKERS = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Executor that runs the message handlers, on virtual threads. Defaults to a shared
     * virtual-thread-per-task executor; Micronaut consumers replace it with the injected
     * {@code BLOCKING} executor via {@link #withHandlerExecutor}. Handler concurrency is
     * bounded by {@link #slots}, not by this executor, so it is never sized or shut down here.
     */
    private volatile ExecutorService pool = DEFAULT_WORKERS;

    /**
     * Gates new intake: a permit is acquired when a lease is picked up and held for the
     * whole lease lifetime (across re-polls), released on terminal ack / eviction /
     * release. This bounds concurrent handlers to {@link #concurrency()} and reserves
     * capacity so in-flight commands' re-polls are never starved by new intake.
     */
    private volatile Semaphore slots;

    /**
     * Schedules delayed re-poll re-submissions for not-yet-terminal commands (Model B).
     */
    private volatile ScheduledExecutorService scheduler;

    /**
     * Renews every in-flight lease on a fixed cadence so an alive consumer keeps ownership.
     */
    private volatile ScheduledExecutorService heartbeat;

    private volatile boolean closed;

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
     *      when no more entries are available. Also the cadence at which a
     *      not-yet-terminal command is re-invoked in-process (Model B).
     */
    protected abstract Duration pollInterval();

    /**
     * @return
     *      The maximum number of message handlers that may run concurrently on this
     *      instance (the worker pool size). Defaults to {@code 1}; subclasses may
     *      override to enable parallel processing.
     */
    protected int concurrency() {
        return 1;
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
     *      The upper bound on a single {@code accept()} invocation before its lease is
     *      released (safety valve); it does not interrupt the handler thread. Defaults
     *      to {@code 15m}.
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
     * Lazily create the worker pool, the re-poll scheduler, the heartbeat daemon and the
     * capacity gate, then start the dispatcher thread. Invoked once, when the first
     * consumer is registered.
     */
    private void startProcessing() {
        // 'slots' — not the executor — bounds how many commands may be in flight at once;
        // handlers run on cheap virtual threads, so the cap is a memory/heartbeat ceiling.
        this.slots = new Semaphore(Math.max(1, concurrency()));
        this.scheduler = new ScheduledThreadPoolExecutor(1, daemonFactory(name() + "-repoll-" + count.get()));
        this.heartbeat = new ScheduledThreadPoolExecutor(1, daemonFactory(name() + "-heartbeat-" + count.get()));
        final long hb = heartbeatInterval().toMillis();
        this.heartbeat.scheduleAtFixedRate(this::heartbeatTick, hb, hb, TimeUnit.MILLISECONDS);
        this.thread = createListenerThread();
    }

    /**
     * Supply the executor used to run message handlers. Micronaut-managed consumers pass the
     * injected {@code @Named(TaskExecutors.BLOCKING)} {@link ExecutorService} (virtual-thread
     * backed on JDK 21) <strong>before</strong> the first {@link #addConsumer}. When not
     * supplied, a shared virtual-thread executor is used. The executor is never shut down by
     * {@link #close()} (it is shared / container-managed).
     *
     * @param executor the shared handler executor; ignored if {@code null}
     */
    public void withHandlerExecutor(@Nullable ExecutorService executor) {
        if (executor != null) {
            this.pool = executor;
        }
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
     * for every queue that has free pool capacity it polls one message (without acking)
     * and submits its processing to the worker pool, then sleeps for {@link #pollInterval()}
     * when nothing was polled this cycle.
     */
    protected void processMessages() {
        log.trace("Work queue - starting dispatcher thread");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean polled = false;
                for (Map.Entry<String, MessageConsumer<M>> entry : listeners.entrySet()) {
                    // poll a queue only when a worker slot is free (backpressure); the
                    // permit is held for the whole lease lifetime so re-polls of in-flight
                    // commands are never starved by new intake
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
     * Poll a single queue (a worker permit has already been acquired by the caller) and,
     * if a message is available, register it as in-flight and submit it to the pool.
     * If nothing is available the permit is released and {@code false} is returned.
     *
     * @return {@code true} if a message was polled and submitted, {@code false} otherwise
     */
    private boolean dispatchOne(String queueId) {
        boolean submitted = false;
        try {
            final WorkQueue.Lease<String> lease = queue.receive(queueId);
            if (lease == null) {
                metrics.recordOutcome(metrics.startSample(), queueId, Outcome.EMPTY);
                return false;
            }
            final var e = new InFlight(queueId, lease.id(), lease.message());
            inFlight.put(e.key(), e);
            submitRun(e);
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

    /**
     * Submit the processing of an in-flight lease to the worker pool. Swallows the
     * rejection that occurs when the pool is being shut down.
     */
    private void submitRun(InFlight e) {
        try {
            pool.execute(() -> run(e));
        }
        catch (RejectedExecutionException ex) {
            log.debug("Work queue - worker pool rejected task for entry={} (shutting down)", e.key());
        }
    }

    /**
     * Runs a single {@code accept()} invocation on a worker thread. On {@code true}
     * (terminal) it acks the message and drops the lease; on {@code false} (Model B,
     * not-yet-terminal) it keeps the lease in-flight and schedules the next invocation
     * after {@link #pollInterval()} — strictly serial per command, since the next
     * invocation is scheduled only after this one returned.
     */
    private void run(InFlight e) {
        final boolean accepted = invokeHandler(e);
        if (accepted) {
            acknowledge(e);
        }
        else if (shouldRepoll(e)) {
            scheduleRepoll(e);
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
        active.put(e.key(), System.currentTimeMillis());
        try {
            accepted = processMessage(e.message(), consumer);
            outcome = accepted ? Outcome.PROCESSED : Outcome.ACTIVE;
        }
        catch (Throwable t) {
            outcome = Outcome.ERRORED;
            log.error("Work queue - error processing entry={} - cause: {}", e.key(), t.getMessage(), t);
        }
        finally {
            active.remove(e.key());
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
        finally {
            releaseLease(e.key());
        }
    }

    /** Whether a not-yet-terminal command should be re-polled: still owned and not shutting down. */
    private boolean shouldRepoll(InFlight e) {
        return !closed && inFlight.containsKey(e.key());
    }

    /**
     * Keep the lease (the heartbeat keeps renewing it, so no reclaim/migration) and schedule
     * the next in-process invocation after {@link #pollInterval()} — strictly serial, since
     * it is scheduled only after the previous invocation returned.
     */
    private void scheduleRepoll(InFlight e) {
        try {
            scheduler.schedule(() -> submitRun(e), pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (RejectedExecutionException ex) {
            log.debug("Work queue - re-poll scheduler rejected entry={} (shutting down)", e.key());
        }
    }

    /**
     * Drop a lease from the in-flight set and free its capacity permit. This pair is the
     * single invariant "a permit is held iff its key is in-flight"; returns {@code true} if
     * this call performed the removal (so callers can log only a real eviction).
     */
    private boolean releaseLease(String key) {
        if (inFlight.remove(key) != null) {
            slots.release();
            return true;
        }
        return false;
    }

    /**
     * Heartbeat tick: renew every in-flight lease so an alive consumer keeps ownership,
     * and release the lease of any single invocation that has exceeded
     * {@link #maxProcessingTime()} (safety valve; does not interrupt the handler thread).
     */
    private void heartbeatTick() {
        final long now = System.currentTimeMillis();
        final long maxMillis = maxProcessingTime().toMillis();
        for (InFlight e : inFlight.values()) {
            final String key = e.key();
            final long start = active.getOrDefault(key, now);
            if (now - start > maxMillis) {
                // a single invocation is stalled beyond the bound: stop renewing so the
                // lease becomes reclaimable, and free its capacity permit
                if (releaseLease(key)) {
                    log.warn("Work queue - releasing lease of stalled entry={} after {} - reclaimable after visibility timeout",
                            key, Duration.ofMillis(now - start));
                }
            }
            else {
                try {
                    queue.renewLease(e.queueId(), e.leaseId());
                }
                catch (Throwable t) {
                    // swallow transient errors; the next tick retries
                    log.warn("Work queue - error renewing lease for entry={} - cause: {}", key, t.getMessage());
                }
            }
        }
    }

    /**
     * Shutdown orderly the queue: stop the dispatcher, cancel pending re-polls, drain
     * the worker pool so active handlers finish and ack, release any remaining leases so
     * they are redelivered, and finally stop the heartbeat daemon.
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
        // 2. cancel pending scheduled re-polls
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        // 3. the handler executor is shared / container-managed — not shut down here;
        //    any active handler finishes on its own (short-lived) and acks
        // 4. release any lease still held so it is redelivered without waiting for lapse
        for (InFlight e : inFlight.values()) {
            if (inFlight.remove(e.key()) != null) {
                try {
                    queue.release(e.queueId(), e.leaseId());
                }
                catch (Throwable t) {
                    log.debug("Work queue - error releasing entry={} on shutdown - cause: {}", e.key(), t.getMessage());
                }
            }
        }
        // 5. stop the heartbeat daemon last (any remaining leases lapse -> peers reclaim)
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }

    public int length(String queueId) {
        return queue.length(queueId);
    }

}
