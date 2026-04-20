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

import io.micronaut.core.annotation.Nullable;

/**
 * Result of a command execution.
 *
 * @param <R> The type of the result value
 */
public record CommandResult<R>(
        CommandStatus status,
        @Nullable R result,
        @Nullable String error
) {

    /**
     * Create a successful result with the given value.
     */
    public static <R> CommandResult<R> success(R result) {
        return new CommandResult<>(CommandStatus.SUCCEEDED, result, null);
    }

    /**
     * Create a failed result with the given error message.
     */
    public static <R> CommandResult<R> failure(String error) {
        return new CommandResult<>(CommandStatus.FAILED, null, error);
    }

    /**
     * Indicate that the command is still running (for long-running commands).
     */
    public static <R> CommandResult<R> running() {
        return new CommandResult<>(CommandStatus.RUNNING, null, null);
    }

    /**
     * Signal that the handler has re-submitted this command to another queue
     * (for example, by calling {@code otherQueue.submit(...)} directly) and the
     * source message should be acknowledged without any state transition. The
     * persisted {@link CommandState} stays in RUNNING until a handler on the
     * destination queue produces a terminal result.
     *
     * <p>Use this when a command's lifecycle should continue on a different
     * queue — typically one with a different claim-timeout, e.g. moving from a
     * slow lifecycle queue (crash-safe for synchronous AWS calls) to a fast
     * monitor queue (low detection lag for status polling) once the initial
     * synchronous work has completed.</p>
     *
     * <p>The handler is responsible for the re-submit itself. Ordering matters:
     * submit to the destination <i>before</i> returning this result, so that if
     * the process crashes between the two the source message is redelivered and
     * re-runs the handler (which must be idempotent).</p>
     */
    public static <R> CommandResult<R> handedOff() {
        return new CommandResult<>(CommandStatus.HANDED_OFF, null, null);
    }

    /**
     * Create a cancelled result.
     */
    public static <R> CommandResult<R> cancelled() {
        return new CommandResult<>(CommandStatus.CANCELLED, null, null);
    }
}
