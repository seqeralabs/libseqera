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
     */
    void start();

    /**
     * Stop consuming commands from the queue.
     * Called during shutdown to gracefully stop processing.
     */
    void stop();
}
