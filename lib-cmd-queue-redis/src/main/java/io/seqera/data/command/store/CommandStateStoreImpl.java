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

import java.time.Duration;
import java.util.Optional;
import java.util.function.UnaryOperator;

import io.seqera.data.command.CommandConfig;
import io.seqera.data.command.CommandState;
import io.seqera.data.store.state.VersionedStateStore;
import io.seqera.data.store.state.impl.StateProvider;
import io.seqera.serde.encode.StringEncodingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State store implementation for command persistence using lib-data-store-state-redis.
 *
 * <p>Automatically uses Redis when RedisActivator bean is present,
 * otherwise falls back to in-memory storage.
 *
 * <p>Instantiated via {@link CommandStateStoreFactory} because Micronaut's annotation
 * processor cannot generate bean definitions for classes extending Groovy base classes.
 *
 * @author Paolo Di Tommaso
 */
public class CommandStateStoreImpl extends VersionedStateStore<CommandState> implements CommandStateStore {

    private static final Logger log = LoggerFactory.getLogger(CommandStateStoreImpl.class);

    private static final String PREFIX = "cmd-state/v1";

    private final Duration ttl;

    /** Bound on the compare-and-swap retry loop in {@link #update} — see {@link CommandConfig#stateUpdateAttempts()}. */
    private final int updateAttempts;

    public CommandStateStoreImpl(StateProvider<String, String> provider, StringEncodingStrategy<CommandState> encodingStrategy, CommandConfig config) {
        super(provider, encodingStrategy);
        this.ttl = config.stateTtl();
        this.updateAttempts = config.stateUpdateAttempts();
        // Fail fast: a non-positive bound would make every update() a silent no-op refusal
        if (updateAttempts <= 0) {
            throw new IllegalStateException("Command state update attempts must be positive - offending value: " + updateAttempts);
        }
    }

    @Override
    protected String getPrefix() {
        return PREFIX;
    }

    @Override
    protected Duration getDuration() {
        return ttl;
    }

    @Override
    public Optional<CommandState> findById(String commandId) {
        return Optional.ofNullable(get(commandId));
    }

    @Override
    public void save(CommandState state) {
        put(state.id(), state);
    }

    /**
     * Compare-and-swap read-modify-write. The mutator is applied to the freshly read state and
     * the write lands only if the entry was not written since that read — the witness is the
     * version the state carries from the read and preserves through its transitions (see
     * {@code VersionedStateStore#replaceIf}, libseqera#107); on a miss the loop re-reads and
     * re-applies, so mere contention is absorbed internally and never surfaced. A {@code false}
     * return means the command is terminal or missing — plus the theoretical exhaustion of the
     * retry bound, which callers already treat as "retry via redelivery". The entry TTL is
     * refreshed on every write, matching {@code put()}.
     */
    @Override
    public boolean update(String commandId, UnaryOperator<CommandState> mutator) {
        for (int i = 0; i < updateAttempts; i++) {
            final CommandState current = get(commandId);
            if (current == null || current.status().isTerminal()) {
                return false;
            }
            final CommandState next = mutator.apply(current);
            if (next == current) {
                return true;    // no-op mutator: stay write-free
            }
            // the witness is this loop's own read, whatever the mutator did to the version
            if (replaceIf(commandId, next.withVersion(current.version()), ttl)) {
                return true;
            }
            // CAS miss: another writer transitioned the state — re-read and re-apply
        }
        log.warn("Command state write did not converge - id={}", commandId);
        return false;
    }

}
