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
package io.seqera.data.command;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.seqera.data.command.store.CommandStateStore;
import io.seqera.lock.Lock;
import io.seqera.lock.LockManager;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the command service.
 *
 * <p>Commands are consumed from the queue on a single poll thread which never runs a handler
 * itself. Instead it dispatches each command to a bounded worker pool and returns immediately, so
 * multiple commands execute concurrently and the poll thread never blocks. A per-command
 * distributed lock ({@link LockManager}) guarantees a command's handler is not executed
 * concurrently on more than one replica; the lock auto-renews while the holder is alive and expires
 * on crash, giving at-least-once recovery.
 *
 * <p>Processing flow for a delivered message (all on the poll thread, fast, non-blocking):
 * <ul>
 *   <li>state missing or already terminal → acknowledge (remove from queue)</li>
 *   <li>no handler registered → mark FAILED, acknowledge</li>
 *   <li>already running on this replica → leave in queue (redelivered later)</li>
 *   <li>lock held by another replica → leave in queue</li>
 *   <li>otherwise → dispatch to the worker pool, leave in queue; it is acknowledged on a later
 *       delivery once the store shows a terminal state</li>
 * </ul>
 *
 * <p>On the worker pool, a {@code SUBMITTED} command (or a {@code RUNNING} command whose handler has
 * no {@code checkStatus} override — i.e. crash recovery of in-process work) runs
 * {@link CommandHandler#execute}; a {@code RUNNING} command whose handler overrides
 * {@code checkStatus} (external-job pattern) runs {@link CommandHandler#checkStatus}. A
 * {@link CommandResult#running()} result leaves the command {@code RUNNING} to be re-polled on a
 * later delivery; a terminal result transitions the command to its terminal state.
 */
@Singleton
public class CommandServiceImpl implements CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandServiceImpl.class);

    @Inject
    private CommandConfig config;

    @Inject
    private CommandStateStore store;

    @Inject
    private CommandQueue queue;

    @Inject
    @Named(CommandLockFactory.COMMAND_LOCK)
    private LockManager lockManager;

    private final Map<String, CommandRegistration<?, ?>> handlers = new ConcurrentHashMap<>();

    /** Whether a handler type overrides {@link CommandHandler#checkStatus} (external-job pattern). */
    private final Map<String, Boolean> checkStatusOverride = new ConcurrentHashMap<>();

    /** Commands currently executing on this replica — atomic dedup gate against self-redelivery. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Bounded pool that runs handlers off the poll thread. Created in {@link #start()}. */
    private volatile ExecutorService pool;

    private volatile boolean started = false;

    @Override
    public void start() {
        if (started) {
            log.debug("Command service already started");
            return;
        }
        started = true;
        this.pool = new ThreadPoolExecutor(
                config.commandPoolSize(), config.commandPoolSize(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.commandPoolQueueSize()),
                workerThreadFactory());
        queue.addConsumer(this::processCommand);
        log.info("Command service started - consuming commands (poolSize={}, queueCapacity={})",
                config.commandPoolSize(), config.commandPoolQueueSize());
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        // stop accepting new work first, then drain in-flight workers so they finish, write their
        // terminal state and release their own locks before pool/lock beans are torn down
        queue.close();
        final var p = pool;
        if (p != null) {
            p.shutdown();
            try {
                if (!p.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Command worker pool did not drain within 30s - forcing shutdown");
                    p.shutdownNow();
                }
            } catch (InterruptedException e) {
                p.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Command service stopped");
    }

    @PreDestroy
    void destroy() {
        stop();
    }

    private static ThreadFactory workerThreadFactory() {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread t = new Thread(runnable, "cmd-queue-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public <P> String submit(Command<P> command) {
        // Create submitted state with params object directly (serialized via @JsonTypeInfo)
        final var state = CommandState.submitted(command.id(), command.type(), command.params());

        // Persist to storage and submit to queue
        store.save(state);
        queue.submit(CommandMsg.of(command.id(), command.type()));

        log.debug("Command submitted: id={}, type={}", command.id(), command.type());
        return command.id();
    }

    @Override
    public Optional<CommandState> getState(String commandId) {
        return store.findById(commandId);
    }

    @Override
    public <R> Optional<R> getResult(String commandId, Class<R> resultType) {
        return getState(commandId)
                .filter(state -> state.status() == CommandStatus.SUCCEEDED)
                .map(CommandState::result)
                .map(resultType::cast);
    }

    @Override
    public boolean cancel(String commandId) {
        final var state = store.findById(commandId).orElse(null);
        if (state == null) {
            return false;
        }

        if (state.status().isTerminal()) {
            return false;
        }

        // Best-effort / advisory: this flips the persisted state to CANCELLED but does NOT interrupt
        // a handler already running on the worker pool. A worker completing in the narrow window
        // after this write may still overwrite it (see runCommand's terminal-write guard). The
        // queued stream entry (if any) is not acked here; it is reclaimed and dropped lazily on a
        // later delivery once the terminal state is observed (same linger as normal completion).
        store.save(state.cancelled());
        log.info("Command cancelled: id={}", commandId);
        return true;
    }

    @Override
    public <P, R> void registerHandler(CommandHandler<P, R> handler) {
        final var registration = CommandRegistration.of(handler);
        handlers.put(handler.type(), registration);
        checkStatusOverride.put(handler.type(), overridesCheckStatus(handler));
        log.debug("Registered command handler: type={}", handler.type());
    }

    @Override
    public CommandRegistration<?, ?> getHandler(String type) {
        return handlers.get(type);
    }

    @Override
    public <P> Command<P> toCommand(CommandState state, CommandRegistration<P, ?> registration) {
        // Use Class.cast() for type-safe runtime casting
        // Safe because @JsonTypeInfo preserves type information during serialization
        final P params = registration.paramsType().cast(state.params());

        return new Command<>() {
            @Override
            public String id() {
                return state.id();
            }

            @Override
            public String type() {
                return state.type();
            }

            @Override
            public P params() {
                return params;
            }
        };
    }

    /**
     * Whether the handler provides its own {@link CommandHandler#checkStatus} implementation (as
     * opposed to inheriting the interface default). Handlers that override it follow the external-job
     * pattern (execute() returns {@code running()} and progress is polled via checkStatus()).
     */
    static boolean overridesCheckStatus(CommandHandler<?, ?> handler) {
        try {
            final Method m = handler.getClass().getMethod("checkStatus", Command.class, CommandState.class);
            return m.getDeclaringClass() != CommandHandler.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static String lockKey(String commandId) {
        return "cmd-queue/lock/" + commandId;
    }

    /**
     * Process a command message received from the queue. Runs on the poll thread and must be fast:
     * it never executes a handler, only dispatches to the worker pool.
     *
     * <p>Return value semantics (controls queue behavior):
     * <ul>
     *   <li>{@code true} = remove the message from the queue (acknowledge)</li>
     *   <li>{@code false} = keep the message for later redelivery</li>
     * </ul>
     *
     * @param msg the command message containing commandId and type
     * @return true to acknowledge, false to retry later
     */
    boolean processCommand(CommandMsg msg) {
        final String id = msg.commandId();

        // Load command state from persistent storage
        final var state = store.findById(id).orElse(null);
        if (state == null) {
            log.error("Command state not found - this should not happen: id={}", id);
            return true;
        }

        // Already terminal (another replica finished it, or it was cancelled): acknowledge & drop.
        // NOTE: this check MUST precede the in-flight check so a completed command is always acked
        // even if a stale in-flight entry lingered.
        if (state.status().isTerminal()) {
            return true;
        }

        // No handler registered for this type: mark FAILED and drop
        final var registration = getHandler(state.type());
        if (registration == null) {
            log.error("No handler for command type: {}", state.type());
            store.save(state.failed("No handler for type: " + state.type()));
            return true;
        }

        // Atomic dedup gate: if we are already running this command on this replica, leave the
        // message in the queue (it will be redelivered) and do nothing. Registering BEFORE the pool
        // submit prevents a fast worker from removing the entry before we record it.
        if (!inFlight.add(id)) {
            return false;
        }

        // Single-runner across replicas: only the lock holder runs the handler
        final Lock lock = lockManager.tryAcquire(lockKey(id));
        if (lock == null) {
            inFlight.remove(id);
            return false;
        }

        // RUNNING + a handler that overrides checkStatus => external-job polling; otherwise execute()
        // (SUBMITTED, or crash recovery of in-process work that was left RUNNING)
        final boolean doCheckStatus = state.status() == CommandStatus.RUNNING
                && checkStatusOverride.getOrDefault(state.type(), Boolean.FALSE);

        try {
            pool.execute(() -> runCommand(id, registration, lock, doCheckStatus));
            return false; // leave unacked; acknowledged on a later delivery once terminal
        } catch (RejectedExecutionException e) {
            // pool saturated → backpressure: undo and leave the message queued for later
            inFlight.remove(id);
            lock.release();
            log.debug("Command worker pool saturated, will retry later: id={}", id);
            return false;
        }
    }

    /**
     * Execute (or check the status of) a command on a worker thread, to completion.
     *
     * @param id            the command id
     * @param registration  the resolved handler registration
     * @param lock          the single-runner lock held for this command; released here
     * @param doCheckStatus true to poll via {@link CommandHandler#checkStatus}, false to run
     *                      {@link CommandHandler#execute}
     */
    <P, R> void runCommand(String id, CommandRegistration<P, R> registration, Lock lock, boolean doCheckStatus) {
        try {
            final var current = store.findById(id).orElse(null);
            if (current == null || current.status().isTerminal()) {
                return; // cancelled or gone before we started
            }

            final Command<P> command = toCommand(current, registration);
            final CommandHandler<P, R> handler = registration.handler();
            final CommandResult<R> result;

            if (doCheckStatus) {
                result = handler.checkStatus(command, current);
            } else {
                if (current.status() != CommandStatus.RUNNING) {
                    store.save(current.started()); // SUBMITTED → RUNNING
                }
                result = handler.execute(command);
            }

            if (result.status() == CommandStatus.RUNNING) {
                // still in progress (external job) - leave RUNNING, re-polled on a later delivery
                return;
            }

            // Terminal result: apply it, but do not clobber a concurrent cancel (best-effort).
            final var latest = store.findById(id).orElse(current);
            if (!latest.status().isTerminal()) {
                store.save(latest.applyResult(result));
                log.debug("Command completed: id={}, status={}", id, result.status());
            }
        } catch (Exception e) {
            log.error("Command processing failed: id={}", id, e);
            final var latest = store.findById(id).orElse(null);
            if (latest != null && !latest.status().isTerminal()) {
                store.save(latest.failed(e.getMessage()));
            }
        } finally {
            // Order matters: the terminal state was written above, before we drop these, so a reclaim
            // racing completion either sees us in-flight (returns false) or sees terminal (acks).
            inFlight.remove(id);
            lock.release();
        }
    }
}
