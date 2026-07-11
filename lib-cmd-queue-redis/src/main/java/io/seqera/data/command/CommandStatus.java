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
 * <p>Wire compatibility: {@code PENDING} and {@code PROCESSING} are the renamed forms of the
 * former {@code SUBMITTED} and {@code RUNNING}. New state is serialized with the new names,
 * while the legacy names are still accepted on read via {@link JsonAlias}, so command state
 * persisted by earlier versions continues to deserialize. Do not remove those aliases.
 */
public enum CommandStatus {
    /** In the queue, awaiting first processing (legacy wire name: {@code "SUBMITTED"}). */
    @JsonAlias("SUBMITTED")
    PENDING,
    /** Being processed by a handler (legacy wire name: {@code "RUNNING"}). */
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
