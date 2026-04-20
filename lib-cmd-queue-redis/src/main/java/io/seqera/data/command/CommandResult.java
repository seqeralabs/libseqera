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
 * @param status        the command execution status
 * @param result        the command result value, or null for non-successful statuses
 * @param error         the error message, or null on success
 * @param targetStream  optional hand-off destination stream id: when non-null, the
 *                      framework ACKs the source message and re-enqueues a fresh
 *                      delivery on the named destination queue (see
 *                      {@link #handoff(String)}). Null for all other results.
 */
public record CommandResult<R>(
        CommandStatus status,
        @Nullable R result,
        @Nullable String error,
        @Nullable String targetStream
) {

    /**
     * Backwards-compatible constructor preserving the original 3-arg signature.
     * Existing call sites using {@code new CommandResult<>(status, result, error)}
     * continue to compile and produce a result with no target stream.
     */
    public CommandResult(CommandStatus status, @Nullable R result, @Nullable String error) {
        this(status, result, error, null);
    }

    /**
     * Create a successful result with the given value.
     */
    public static <R> CommandResult<R> success(R result) {
        return new CommandResult<>(CommandStatus.SUCCEEDED, result, null, null);
    }

    /**
     * Create a failed result with the given error message.
     */
    public static <R> CommandResult<R> failure(String error) {
        return new CommandResult<>(CommandStatus.FAILED, null, error, null);
    }

    /**
     * Indicate that the command is still in progress and should be redelivered
     * for further polling via {@link CommandHandler#checkStatus}.
     *
     * <p>Named "active" rather than "running" to avoid confusion with domain-level
     * "running" statuses (e.g. a task whose container is executing on the backend):
     * an "active" command covers the whole non-terminal lifecycle, including states
     * where the underlying work has not yet started.</p>
     */
    public static <R> CommandResult<R> active() {
        return new CommandResult<>(CommandStatus.RUNNING, null, null, null);
    }

    /**
     * Hand off the in-flight command to a different queue. From the source queue's
     * perspective this is a terminal result: the framework ACKs the source message
     * and re-enqueues a fresh delivery on the named destination stream. From the
     * command's lifecycle perspective the command is still active — subsequent
     * {@code checkStatus()} invocations happen on the destination queue and drive
     * it to a terminal status there.
     *
     * <p>The destination must be the {@code streamName()} of a {@link CommandQueue}
     * attached to the same {@link CommandService} (primary or via
     * {@link CommandService#attachQueue(CommandQueue)}); the service uses its
     * internal registry to route the hand-off through the owning queue so consumer
     * group and configuration are correct. Unknown destinations are rejected.</p>
     *
     * <p>Typical use: a command whose polling cadence should change after a certain
     * point in its lifecycle — for example, a task-submit command moving from a
     * lifecycle queue (long claim-timeout, crash-safe for slow AWS calls) to a
     * monitor queue (short claim-timeout, low detection lag) once the initial
     * launch has succeeded.</p>
     *
     * @param dstStreamId the stream id of the destination queue
     */
    public static <R> CommandResult<R> handoff(String dstStreamId) {
        if (dstStreamId == null || dstStreamId.isBlank()) {
            throw new IllegalArgumentException("Destination stream id must not be null or blank");
        }
        return new CommandResult<>(CommandStatus.RUNNING, null, null, dstStreamId);
    }

    /**
     * Indicate that the command is still running (for long-running commands).
     *
     * @deprecated Use {@link #active()} instead. This alias is preserved for
     *             backwards compatibility and will be removed in a future release.
     */
    @Deprecated(since = "0.4.0", forRemoval = true)
    public static <R> CommandResult<R> running() {
        return active();
    }

    /**
     * Create a cancelled result.
     */
    public static <R> CommandResult<R> cancelled() {
        return new CommandResult<>(CommandStatus.CANCELLED, null, null, null);
    }
}
