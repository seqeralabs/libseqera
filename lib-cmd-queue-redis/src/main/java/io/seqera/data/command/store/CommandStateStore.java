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
package io.seqera.data.command.store;

import java.util.Optional;

import io.seqera.data.command.CommandState;

/**
 * State store for command persistence.
 *
 * @author Paolo Di Tommaso
 */
public interface CommandStateStore {

    /**
     * Save a command state.
     *
     * @param state the command state to save
     */
    void save(CommandState state);

    /**
     * Find a command state by ID.
     *
     * @param commandId the command ID
     * @return the command state, or empty if not found
     */
    Optional<CommandState> findById(String commandId);

    /**
     * Clear all stored commands. Useful for test cleanup.
     */
    void clear();
}
