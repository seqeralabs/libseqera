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
 * Status of a command in the queue.
 */
public enum CommandStatus {
    /** Created, not yet submitted to queue */
    PENDING,
    /** In queue, waiting for pickup */
    SUBMITTED,
    /** Being executed */
    RUNNING,
    /** Completed successfully */
    SUCCEEDED,
    /** Completed with error */
    FAILED,
    /** Cancelled by user */
    CANCELLED;

    /**
     * Check if the status is terminal (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
