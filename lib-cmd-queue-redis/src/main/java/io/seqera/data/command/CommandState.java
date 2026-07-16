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

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.micronaut.core.annotation.Nullable;

/**
 * Persistent state of a command, stored as JSON in the database.
 * Uses @JsonTypeInfo to preserve type information for params and result
 * during serialization, enabling proper deserialization without explicit type knowledge.
 *
 * <p>Error-tracking fields ({@code errorsCount}, {@code lastError}) capture processing
 * errors that did <em>not</em> terminally fail the command — a handler that threw is retried
 * (see {@code CommandServiceImpl}), and these record how many consecutive times it has thrown and
 * the most recent message, for observability of a retry storm on an otherwise non-terminal command.
 * Distinct from {@code error}, which is the terminal failure reason (set only when FAILED).
 * {@code modifiedAt} is refreshed on every state write, giving a last-touched timestamp.
 *
 * @param id command id
 * @param type command type discriminator
 * @param status current lifecycle status
 * @param params command parameters (polymorphic, type preserved via {@code @JsonTypeInfo})
 * @param result terminal result payload, if any (polymorphic)
 * @param error terminal failure reason (non-null only when {@code status == FAILED})
 * @param errorsCount number of consecutive processing errors since the last successful
 *        processing; reset to 0 on any successful transition or recovery
 * @param lastError message of the most recent processing error, transient or terminal (nullable)
 * @param createdAt when the command was first submitted
 * @param startedAt when the command first transitioned to RUNNING (nullable)
 * @param modifiedAt when the command state was last written (nullable for pre-existing records)
 * @param completedAt when the command reached a terminal state (nullable)
 */
public record CommandState(
        String id,
        String type,
        CommandStatus status,
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
        Object params,
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
        @Nullable Object result,
        @Nullable String error,
        int errorsCount,
        @Nullable String lastError,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant modifiedAt,
        @Nullable Instant completedAt
) {

    /**
     * Create a new submitted command state.
     */
    public static CommandState submitted(String id, String type, Object params) {
        final Instant now = Instant.now();
        return new CommandState(
                id, type, CommandStatus.SUBMITTED, params,
                null, null, 0, null, now, null, now, null
        );
    }

    /**
     * Transition to RUNNING status. A successful (non-throwing) transition, so the
     * consecutive-error streak is reset.
     */
    public CommandState started() {
        return new CommandState(
                id, type, CommandStatus.RUNNING, params,
                result, error, 0, lastError, createdAt, Instant.now(), Instant.now(), completedAt
        );
    }

    /**
     * Transition to SUCCEEDED status with result.
     */
    public CommandState completed(Object result) {
        final Instant now = Instant.now();
        return new CommandState(
                id, type, CommandStatus.SUCCEEDED, params,
                result, null, 0, lastError, createdAt, startedAt, now, now
        );
    }

    /**
     * Transition to FAILED status with error.
     */
    public CommandState failed(String error) {
        final Instant now = Instant.now();
        return new CommandState(
                id, type, CommandStatus.FAILED, params,
                null, error, errorsCount, error, createdAt, startedAt, now, now
        );
    }

    /**
     * Transition to CANCELLED status.
     */
    public CommandState cancelled() {
        final Instant now = Instant.now();
        return new CommandState(
                id, type, CommandStatus.CANCELLED, params,
                null, null, 0, lastError, createdAt, startedAt, now, now
        );
    }

    /**
     * Record a non-terminal processing error: keep the current status (the command stays retryable),
     * increment the consecutive-error count, capture the message, and refresh {@code modifiedAt}.
     * Called when a handler throws and the command is kept in the queue for retry.
     */
    public CommandState withError(String message) {
        return new CommandState(
                id, type, status, params,
                result, error, errorsCount + 1, message, createdAt, startedAt, Instant.now(), completedAt
        );
    }

    /**
     * Clear the consecutive-error streak after a recovery, without changing status. Refreshes
     * {@code modifiedAt}. {@code lastError} is retained as a historical marker of the last error seen.
     */
    public CommandState clearErrors() {
        return new CommandState(
                id, type, status, params,
                result, error, 0, lastError, createdAt, startedAt, Instant.now(), completedAt
        );
    }

    /**
     * Apply a command result to transition to the appropriate terminal state.
     *
     * @param result The command result
     * @return The new state with the result applied
     */
    public CommandState applyResult(CommandResult<?> result) {
        return switch (result.status()) {
            case SUCCEEDED -> completed(result.result());
            case FAILED -> failed(result.error());
            case CANCELLED -> cancelled();
            default -> failed("Unexpected result status: " + result.status());
        };
    }
}
