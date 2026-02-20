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
 */
package io.seqera.data.command;

import java.time.Instant;

/**
 * Message specification for the command queue.
 * Contains the minimal information needed to retrieve and process a command.
 *
 * @param commandId   The unique identifier of the command
 * @param type        The command type (for routing to handler)
 * @param submittedAt When the command was submitted to the queue
 */
public record CommandMsg(
        String commandId,
        String type,
        Instant submittedAt
) {

    /**
     * Create a command msg for a given command ID and type.
     */
    public static CommandMsg of(String commandId, String type) {
        return new CommandMsg(commandId, type, Instant.now());
    }
}
