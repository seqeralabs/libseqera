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

import java.time.Duration;
import java.util.Optional;

/**
 * Service for submitting and managing commands.
 */
public interface CommandService {

    /**
     * Submit a command for execution (fire-and-forget).
     *
     * @param command The command to submit
     * @param <P>     The type of command parameters
     * @return The command ID
     */
    <P> String submit(Command<P> command);

    /**
     * Get the current state of a command.
     *
     * @param commandId The command ID
     * @return The command state, or empty if not found
     */
    Optional<CommandState> getState(String commandId);

    /**
     * Get the typed result of a completed command.
     *
     * @param commandId  The command ID
     * @param resultType The expected result type class for type-safe casting
     * @param <R>        The result type
     * @return The result, or empty if not found or not completed
     */
    <R> Optional<R> getResult(String commandId, Class<R> resultType);

    /**
     * Cancel a command (only if not yet terminal).
     *
     * @param commandId The command ID
     * @return true if cancelled, false if already terminal or not found
     */
    boolean cancel(String commandId);

    /**
     * Register a handler for a command type.
     * Generic types P and R are extracted via reflection.
     *
     * @param handler The handler to register
     * @param <P>     The type of command parameters
     * @param <R>     The type of command result
     */
    <P, R> void registerHandler(CommandHandler<P, R> handler);

    /**
     * Get a handler registration by type.
     *
     * @param type The command type
     * @return The handler registration, or null if not found
     */
    CommandRegistration<?, ?> getHandler(String type);

    /**
     * Create a command object from a state and handler registration.
     * Uses the registration's paramsType for type-safe casting.
     *
     * @param state        The command state (params will be cast using paramsType)
     * @param registration The handler registration containing type information
     * @param <P>          The type of command parameters
     * @return The command object with typed params
     */
    <P> Command<P> toCommand(CommandState state, CommandRegistration<P, ?> registration);

    /**
     * Start consuming commands from the queue.
     * Must be called AFTER all handlers are registered to avoid race conditions
     * where messages are processed before handlers are available.
     *
     * <p>Also clears the shutdown signal raised by {@link #stop()} or {@link #drain(Duration)}, so a
     * service started again routes deliveries to their handlers instead of refusing them.
     */
    void start();

    /**
     * Stop consuming commands from the queue.
     * Called during shutdown to gracefully stop processing.
     *
     * <p>This releases the queue immediately and does not wait for handler executions already in
     * progress. Prefer {@link #drain(Duration)} when those executions depend on resources — a
     * database connection pool, for instance — that are about to be torn down.
     *
     * <p>It also raises the shutdown signal: a delivery claimed just before this call is settled for
     * redelivery rather than routed to its handler. Only work not yet started is declined — an
     * execution already under way is unaffected.
     */
    void stop();

    /**
     * Stop claiming new commands and wait for the ones already being handled to finish.
     *
     * <p>Intended to be called while the rest of the application is still alive, so that a handler
     * mid-execution can complete its work and record its outcome instead of failing against
     * resources that have already been closed. On return the queue is released, as with
     * {@link #stop()}.
     *
     * <p>Raises the shutdown signal on entry, with the same effect as {@link #stop()}: a delivery
     * claimed just before the call is settled for redelivery rather than routed, so the wait below is
     * not extended by work begun after it started.
     *
     * <p>Deliberately framework-agnostic: the caller decides what triggers it and how the timeout
     * relates to any container-level shutdown budget.
     *
     * @param timeout
     *      how long to wait for in-flight commands to finish
     * @return
     *      {@code true} if nothing was left running, {@code false} if the timeout was reached with
     *      commands still in flight — the caller may then log, report, or proceed regardless
     */
    boolean drain(Duration timeout);

    /**
     * @return the number of command handler executions currently in progress
     */
    int activeCommands();
}
