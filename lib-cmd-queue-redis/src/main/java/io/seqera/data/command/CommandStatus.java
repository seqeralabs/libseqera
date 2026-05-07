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
    /** Canceled by user */
    CANCELLED,
    /**
     * Pseudo-status used only by {@link CommandResult#handedOff()} — signals to the
     * framework that the handler has re-submitted this command to another queue
     * (e.g. via a directly-injected {@link CommandQueue}) and the source message
     * should be acknowledged without persisting any state transition. Not used for
     * persisted {@link CommandState}: the stored command remains in RUNNING until
     * a handler on the destination queue produces a terminal result.
     */
    HANDED_OFF;

    /**
     * Check if the status is terminal (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
