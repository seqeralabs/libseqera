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

package io.seqera.data.command;

/**
 * Handler for executing commands of a specific type.
 * Generic types P (params) and R (result) are extracted via reflection at registration time.
 *
 * @param <P> The type of command parameters
 * @param <R> The type of command result
 */
public interface CommandHandler<P, R> {

    /**
     * The command type this handler processes.
     */
    String type();

    /**
     * Execute the command and return a result.
     * This method is executed asynchronously via an executor service.
     * If execution takes longer than 1 second, the command is marked as RUNNING and
     * {@link #checkStatus} will be called periodically to check completion.
     * For long-running commands, return {@link CommandResult#running()} to indicate
     * the operation is in progress.
     *
     * @param command The command to execute
     * @return The result of the execution
     */
    CommandResult<R> execute(Command<P> command);

    /**
     * Check the status of a long-running command.
     * Called periodically for commands in RUNNING state until a terminal status is returned.
     * The command parameter provides typed access to params via {@code command.params()}.
     * The state parameter provides access to timing and status information.
     *
     * @param command The command being checked (provides typed params access)
     * @param state The current command state (timing, status info)
     * @return The result indicating current status (RUNNING to continue, or terminal status)
     */
    default CommandResult<R> checkStatus(Command<P> command, CommandState state) {
        return CommandResult.running();
    }
}
