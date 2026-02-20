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

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.micronaut.core.annotation.Nullable;

/**
 * Persistent state of a command, stored as JSON in the database.
 * Uses @JsonTypeInfo to preserve type information for params and result
 * during serialization, enabling proper deserialization without explicit type knowledge.
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
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt
) {

    /**
     * Create a new submitted command state.
     */
    public static CommandState submitted(String id, String type, Object params) {
        return new CommandState(
                id, type, CommandStatus.SUBMITTED, params,
                null, null, Instant.now(), null, null
        );
    }

    /**
     * Transition to RUNNING status.
     */
    public CommandState started() {
        return new CommandState(
                id, type, CommandStatus.RUNNING, params,
                result, error, createdAt, Instant.now(), completedAt
        );
    }

    /**
     * Transition to SUCCEEDED status with result.
     */
    public CommandState completed(Object result) {
        return new CommandState(
                id, type, CommandStatus.SUCCEEDED, params,
                result, null, createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Transition to FAILED status with error.
     */
    public CommandState failed(String error) {
        return new CommandState(
                id, type, CommandStatus.FAILED, params,
                null, error, createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Transition to CANCELLED status.
     */
    public CommandState cancelled() {
        return new CommandState(
                id, type, CommandStatus.CANCELLED, params,
                null, null, createdAt, startedAt, Instant.now()
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
