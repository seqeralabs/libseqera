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
     *
     * <p>This method runs on a bounded worker pool, off the queue poll thread, and may block for as
     * long as needed — the command is marked {@code RUNNING} for its whole duration and no timeout is
     * applied. There are two supported patterns:
     * <ul>
     *   <li><b>In-process:</b> do the work and return a terminal {@link CommandResult}
     *       ({@code success}/{@code failure}). No {@link #checkStatus} override is needed.</li>
     *   <li><b>External job:</b> kick off external work, return {@link CommandResult#running()}, and
     *       override {@link #checkStatus} to poll for completion on subsequent deliveries.</li>
     * </ul>
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
