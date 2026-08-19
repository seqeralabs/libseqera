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

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Status of a command in the queue.
 *
 * <p>{@code PENDING} and {@code PROCESSING} were named {@code SUBMITTED} and {@code RUNNING}
 * before the queue vocabulary was aligned with {@code WorkQueue}. The {@link JsonAlias}
 * annotations are what keep state written by the previous naming readable: {@code CommandState}
 * is persisted as Jackson-encoded JSON with the status as a bare enum name, so an entry stored
 * as {@code "SUBMITTED"} or {@code "RUNNING"} — by an older replica during a rolling deploy, or
 * before it, for as long as the stored state lives — only deserializes because of them. They must
 * never be removed.
 */
public enum CommandStatus {
    /** In queue, awaiting first processing */
    @JsonAlias("SUBMITTED")
    PENDING,
    /** Being processed by a handler */
    @JsonAlias("RUNNING")
    PROCESSING,
    /** Completed successfully */
    SUCCEEDED,
    /** Completed with error */
    FAILED,
    /** Canceled by user */
    CANCELLED;

    /**
     * Check if the status is terminal (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
