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

import io.seqera.data.command.CommandState;
import io.seqera.data.store.state.AbstractStateStore;
import io.seqera.data.store.state.impl.StateProvider;
import io.seqera.serde.encode.StringEncodingStrategy;

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
public class CommandStateStoreImpl extends AbstractStateStore<CommandState> implements CommandStateStore {

    private static final String PREFIX = "cmd-state/v1";
    private static final String ATTEMPT_PREFIX = "cmd-attempt/v1:";

    private final Duration ttl;
    private final StateProvider<String, String> provider;

    public CommandStateStoreImpl(StateProvider<String, String> provider, StringEncodingStrategy<CommandState> encodingStrategy, Duration ttl) {
        super(provider, encodingStrategy);
        this.provider = provider;
        this.ttl = ttl;
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

    @Override
    public boolean create(CommandState state) {
        return putIfAbsent(state.id(), state, ttl);
    }

    @Override
    public boolean tryAcquireAttempt(String commandId, String owner, Duration lease) {
        return provider.putIfAbsent(ATTEMPT_PREFIX + commandId, owner, lease);
    }

    @Override
    public boolean renewAttempt(String commandId, String owner, Duration lease) {
        return provider.compareAndSet(ATTEMPT_PREFIX + commandId, owner, owner, lease);
    }

    @Override
    public boolean isAttemptOwner(String commandId, String owner) {
        return owner.equals(provider.get(ATTEMPT_PREFIX + commandId));
    }

    @Override
    public boolean releaseAttempt(String commandId, String owner) {
        return provider.compareAndDelete(ATTEMPT_PREFIX + commandId, owner);
    }

    @Override
    public boolean saveOwned(CommandState state, String owner) {
        return provider.putIfOwner(
                ATTEMPT_PREFIX + state.id(),
                owner,
                key0(state.id()),
                serialize(state),
                ttl);
    }

}
