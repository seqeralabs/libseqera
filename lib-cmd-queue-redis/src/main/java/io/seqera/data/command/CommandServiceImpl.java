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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import io.micronaut.scheduling.TaskExecutors;
import io.seqera.data.command.store.CommandStateStore;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the command service.
 * Handles queue consumption and command execution with proper multi-replica support.
 *
 * <p>Processing runs on the shared worker pool of the underlying message stream, so
 * neither {@code execute()} nor {@code checkStatus()} blocks the dispatcher loop and no
 * per-command timeout is needed. Cross-replica single-runner exclusion comes from the
 * stream's per-message lease.
 *
 * <p>Processing flow:
 * <ul>
 *   <li>If command is already RUNNING → call checkStatus()</li>
 *   <li>If command is not yet RUNNING → call execute()</li>
 *   <li>If result is RUNNING → mark as RUNNING and return false (re-polled later)</li>
 *   <li>If result is terminal → apply result and return true (message removed from queue)</li>
 *   <li>If the handler throws → return false so the message is retried; a throw is treated as
 *       transient, never as a terminal failure (deciding permanent failure is the domain
 *       layer's job, see seqeralabs/sched#712)</li>
 * </ul>
 */
@Singleton
public class CommandServiceImpl implements CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandServiceImpl.class);

    @Inject
    private CommandStateStore store;

    @Inject
    private CommandQueue queue;

    @Inject
    @Named(TaskExecutors.BLOCKING)
    private ExecutorService blockingExecutor;

    private final Map<String, CommandRegistration<?, ?>> handlers = new ConcurrentHashMap<>();

    private volatile boolean started = false;

    @Override
    public void start() {
        if (started) {
            log.debug("Command service already started");
            return;
        }
        started = true;
        // run handlers on the shared Micronaut BLOCKING (virtual-thread) executor
        queue.withHandlerExecutor(blockingExecutor);
        queue.addConsumer(this::processCommand);
        log.info("Command service started - consuming commands");
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        queue.close();
        log.info("Command service stopped");
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

        store.save(state.cancelled());
        log.info("Command cancelled: id={}", commandId);
        return true;
    }

    @Override
    public <P, R> void registerHandler(CommandHandler<P, R> handler) {
        final var registration = CommandRegistration.of(handler);
        handlers.put(handler.type(), registration);
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
     * Process a command message received from the queue.
     *
     * <p>This is the entry point for queue message consumption. It performs initial
     * validation and state lookup, then delegates to the type-safe handler method.
     *
     * <p>Return value semantics (controls queue behavior):
     * <ul>
     *   <li>{@code true} = command fully processed, remove message from queue</li>
     *   <li>{@code false} = command needs retry, keep message in queue for redelivery</li>
     * </ul>
     *
     * @param msg The command message containing commandId and type
     * @return true to acknowledge (remove from queue), false to retry later
     */
    private boolean processCommand(CommandMsg msg) {
        // Step 1: Load command state from persistent storage
        var state = store.findById(msg.commandId()).orElse(null);
        if (state == null) {
            log.error("Command state not found - this should not happen: id={}", msg.commandId());
            return true;
        }

        // Step 2: Check if command already reached a terminal state (SUCCEEDED/FAILED/CANCELLED)
        // This can happen if another replica processed it, or if it was cancelled.
        // Return true to remove the now-stale message from the queue.
        if (state.status().isTerminal()) {
            return true;
        }

        // Step 3: Look up the registered handler for this command type
        // If no handler is registered, mark as FAILED and remove from queue.
        final var registration = getHandler(state.type());
        if (registration == null) {
            log.error("No handler for command type: {}", state.type());
            store.save(state.failed("No handler for type: " + state.type()));
            return true;
        }

        // Step 4: Delegate to the type-capturing helper method
        // This pattern allows Java to infer concrete type parameters (P, R) from the
        // CommandRegistration, enabling type-safe handler invocation without raw types.
        return processCommandWithHandler(msg, state, registration);
    }

    /**
     * Execute command processing with a specific handler registration.
     *
     * <p>This helper method captures the type parameters {@code <P, R>} from the
     * {@link CommandRegistration}, allowing type-safe interaction with the handler.
     *
     * <p>Runs directly on the shared worker pool thread (no timeout, no extra executor):
     * <ol>
     *   <li>If command is already RUNNING → call {@code checkStatus()} to poll for completion</li>
     *   <li>If command is not yet RUNNING → call {@code execute()}</li>
     *   <li>If result status is RUNNING → mark RUNNING and return false (re-polled later)</li>
     *   <li>If result status is terminal → update state and return true (done)</li>
     * </ol>
     *
     * @param msg The original queue message (for logging)
     * @param state The current command state from storage (wildcard types)
     * @param registration The handler registration with type parameters captured
     * @param <P> The command parameter type
     * @param <R> The command result type
     * @return true to acknowledge (remove from queue), false to retry later
     */
    private <P, R> boolean processCommandWithHandler(
            CommandMsg msg,
            CommandState state,
            CommandRegistration<P, R> registration) {

        // Reconstruct the typed Command object from persisted state
        // Uses Class.cast() internally for type-safe conversion
        final Command<P> command = toCommand(state, registration);
        final CommandHandler<P, R> handler = registration.handler();

        try {
            // Branch on the command status. Only SUBMITTED and RUNNING are reachable here
            // (processCommand already acked terminal states); any other value is a bug or a
            // newly-added status and must fail loudly rather than be silently executed. Both
            // execute() and checkStatus() run on the shared worker pool, so a slow handler
            // does not block the loop.
            final CommandResult<R> result = switch (state.status()) {
                case SUBMITTED -> handler.execute(command);
                case RUNNING -> handler.checkStatus(command, state);
                default -> throw new IllegalStateException("Unexpected command status: " + state.status() + " - id=" + state.id());
            };

            // Handler returned a result - check if command is still in progress
            if (result.status() == CommandStatus.RUNNING) {
                // Handler explicitly returned RUNNING (e.g., async job not yet complete)
                // Ensure state reflects RUNNING status for accurate reporting
                if (state.status() != CommandStatus.RUNNING) {
                    store.save(state.started());
                } else if (state.errorsCount() > 0) {
                    // Recovered after one or more transient errors — reset the streak. Single write,
                    // and only when there is something to reset, so healthy re-polls stay write-free.
                    store.save(state.clearErrors());
                }
                return false; // Keep in queue - re-polled and will call checkStatus()
            }

            // Terminal result (SUCCEEDED, FAILED, or CANCELLED)
            // Apply the result to transition to terminal state
            final CommandState newState = state.applyResult(result);
            store.save(newState);
            log.debug("Command completed: id={}, status={}", state.id(), newState.status());
            return true; // Remove from queue - processing complete

        } catch (Exception e) {
            // A thrown handler is a transient/retryable condition, NOT a terminal command
            // outcome: keep the message in the queue (return false) so the stream layer retains
            // its lease and re-polls it. A genuine command failure is signalled by returning a
            // FAILED CommandResult (handled above), never by throwing. Persisting FAILED + acking
            // here would turn a transient/infra error (e.g. the Postgres pool closing during
            // shutdown) into a permanent FAILED command while the domain entity is left
            // non-terminal, stranding the work. Deciding a command has *permanently* failed is
            // delegated to the domain layer that owns the entity state (see seqeralabs/sched#712).
            log.error("Command processing errored, will retry: id={}", msg.commandId(), e);
            recordError(state, e);
            return false; // Keep in queue - redelivered / re-polled
        }
    }

    /**
     * Best-effort: record a non-terminal processing error on the command state — increment the
     * consecutive-error count and capture the message — for observability of a retry storm on a
     * command that stays retryable. A failure to persist this must not change control flow: the
     * command is kept in the queue and retried regardless.
     */
    private void recordError(CommandState state, Exception e) {
        try {
            store.save(state.withError(e.getMessage()));
        } catch (Exception saveErr) {
            log.warn("Failed to record command error state: id={}", state.id(), saveErr);
        }
    }
}
