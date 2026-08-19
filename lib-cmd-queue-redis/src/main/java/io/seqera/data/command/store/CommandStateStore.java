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
package io.seqera.data.command.store;

import java.util.Optional;
import java.util.function.UnaryOperator;

import io.seqera.data.command.CommandState;

/**
 * State store for command persistence.
 *
 * @author Paolo Di Tommaso
 */
public interface CommandStateStore {

    /**
     * Store a command state, overwriting unconditionally.
     *
     * <p><b>Create only.</b> Because the write is unconditional, using this to transition an
     * existing command discards whatever another writer stored in the meantime — a concurrent
     * {@code cancel()} against a completing command loses one of the two outcomes. Use
     * {@link #update(String, UnaryOperator)} for every transition; this method is for the initial
     * {@code PENDING} record, where there is nothing to overwrite.
     *
     * @param state the command state to store
     */
    void save(CommandState state);

    /**
     * Transition an existing command through a compare-and-swap read-modify-write, so a
     * concurrent writer cannot discard the result.
     *
     * <p>The mutator is applied to the state as freshly read, never to a snapshot the caller
     * read earlier; the write lands only if no other writer got in between, and a miss re-reads
     * and re-applies (bounded by {@code CommandConfig#stateUpdateAttempts()}) — mere contention
     * is absorbed internally and never surfaced.
     *
     * <p>Returns {@code false} rather than throwing when the transition did not happen: the
     * command no longer exists, has already reached a terminal state — or, theoretically, the
     * CAS retry bound was exhausted against a live command. Callers whose write is best-effort
     * (error bookkeeping) may ignore the result; callers whose write is load-bearing must
     * distinguish the terminal case from the exhausted one (re-read the status) and leave the
     * work queued so it is retried.
     *
     * <p>The CAS witness is the version stamped by the store on every write and carried by the
     * state through its transitions (see {@code VersionAware}) — never the serialized form of
     * the state, so it holds across replicas whose JVMs serialize the same value differently,
     * and adding a field to {@link CommandState} does not affect records written before the
     * change (they are adopted by their first successful update).
     *
     * <p><b>Deployment note:</b> a replica still running the pre-versioning code cannot update an
     * entry a versioned replica has written — its byte comparison sees the version frame the store
     * prepends to the stored form and never matches, while the read itself keeps succeeding
     * silently (unknown properties are ignored). Old replicas therefore keep refusing every such
     * transition — a missed PROCESSING mark makes redelivery re-run {@code execute()} instead of
     * {@code checkStatus()}, duplicating the work — until every replica runs the versioned code.
     * Validate multi-replica behavior only once the rollout is complete.
     *
     * @param commandId the command to transition
     * @param mutator   derives the new state from the current one
     * @return {@code true} if the new state was stored
     */
    boolean update(String commandId, UnaryOperator<CommandState> mutator);

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
