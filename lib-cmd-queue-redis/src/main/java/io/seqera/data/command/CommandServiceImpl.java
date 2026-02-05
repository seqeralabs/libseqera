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
 */
package io.seqera.data.command;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <p>Processing flow:
 * <ul>
 *   <li>If command is already RUNNING → call checkStatus() synchronously</li>
 *   <li>If command is not RUNNING → execute asynchronously with 1-second timeout:
 *       <ul>
 *         <li>If completes within timeout → process result immediately</li>
 *         <li>If times out → mark as RUNNING, retry later via queue</li>
 *       </ul>
 *   </li>
 *   <li>If result is RUNNING → return false (message stays in queue for retry)</li>
 *   <li>If result is terminal → return true (message removed from queue)</li>
 * </ul>
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
    @Named(TaskExecutors.BLOCKING)
    private ExecutorService executor;

    private final Map<String, CommandRegistration<?, ?>> handlers = new ConcurrentHashMap<>();

    private volatile boolean started = false;

    @Override
    public void start() {
        if (started) {
            log.debug("Command service already started");
            return;
        }
        started = true;
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
     * <p>Processing flow:
     * <ol>
     *   <li>If command is already RUNNING → call {@code checkStatus()} to poll for completion</li>
     *   <li>If command is not yet RUNNING → call {@code execute()} with timeout:
     *       <ul>
     *         <li>If completes within timeout → process the result immediately</li>
     *         <li>If times out → mark as RUNNING, return false to retry later</li>
     *       </ul>
     *   </li>
     *   <li>If result status is RUNNING → return false (keep in queue for polling)</li>
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
            CommandResult<R> result;

            // Branch based on current command status
            if (state.status() == CommandStatus.RUNNING) {
                // Command was previously marked as RUNNING (long-running async operation)
                // Call checkStatus() to poll the external system for completion
                result = handler.checkStatus(command, state);
            } else {
                // Command not yet running (status is SUBMITTED)
                // Execute with timeout to avoid blocking the queue processor indefinitely
                result = executeWithTimeout(handler, command);

                // Timeout case: execute() is still running in background thread
                // Mark state as RUNNING so next delivery will call checkStatus() instead
                if (result == null) {
                    store.save(state.started());
                    return false; // Keep in queue - will retry and call checkStatus()
                }
            }

            // Handler returned a result - check if command is still in progress
            if (result.status() == CommandStatus.RUNNING) {
                // Handler explicitly returned RUNNING (e.g., async job not yet complete)
                // Ensure state reflects RUNNING status for accurate reporting
                if (state.status() != CommandStatus.RUNNING) {
                    store.save(state.started());
                }
                return false; // Keep in queue - will retry and call checkStatus()
            }

            // Terminal result (SUCCEEDED, FAILED, or CANCELLED)
            // Apply the result to transition to terminal state
            final CommandState newState = state.applyResult(result);
            store.save(newState);
            log.debug("Command completed: id={}, status={}", state.id(), newState.status());
            return true; // Remove from queue - processing complete

        } catch (Exception e) {
            // Unexpected exception during processing - mark as FAILED
            log.error("Command processing failed: id={}", msg.commandId(), e);
            store.save(state.failed(e.getMessage()));
            return true; // Remove from queue - no point retrying a crashed handler
        }
    }

    /**
     * Execute a command handler with a timeout.
     *
     * <p>Submits the handler's {@code execute()} method to a thread pool and waits
     * up to {@code config.executeTimeout()} for completion. This prevents slow handlers from
     * blocking the queue processor thread.
     *
     * <p>Timeout behavior: If the handler doesn't complete within the timeout,
     * this method returns {@code null} but the handler continues executing in the
     * background. The caller should mark the command as RUNNING and retry later
     * via {@code checkStatus()}.
     *
     * @param handler The command handler to execute
     * @param command The command with parameters
     * @param <P> The command parameter type
     * @param <R> The command result type
     * @return The result if completed within timeout, or {@code null} if timed out
     * @throws RuntimeException if the handler throws an exception
     */
    private <P, R> CommandResult<R> executeWithTimeout(CommandHandler<P, R> handler, Command<P> command) {
        // Submit handler execution to thread pool for async execution
        final Future<CommandResult<R>> future = executor.submit(() -> handler.execute(command));

        try {
            // Block until result is available or timeout expires
            return future.get(config.executeTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Handler is taking longer than allowed - let it continue in background
            // Caller will mark as RUNNING and poll via checkStatus() on retry
            return null;
        } catch (Exception e) {
            // Handler threw an exception - cancel the future and propagate
            future.cancel(true);
            throw new RuntimeException("Command execution failed", e);
        }
    }
}
