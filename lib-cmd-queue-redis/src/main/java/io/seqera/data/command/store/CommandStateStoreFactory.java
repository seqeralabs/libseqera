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

import io.micronaut.context.annotation.Factory;
import io.seqera.data.command.CommandConfig;
import io.seqera.data.command.CommandState;
import io.seqera.data.store.state.impl.StateProvider;
import io.seqera.serde.jackson.JacksonEncodingStrategy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Factory for creating CommandStateStore bean.
 *
 * <p>Uses a factory because Micronaut's annotation processor cannot
 * generate bean definitions for classes extending Groovy base classes.
 *
 * @author Paolo Di Tommaso
 */
@Factory
public class CommandStateStoreFactory {

    @Inject
    private CommandConfig config;

    @Singleton
    public CommandStateStore commandStateStore(StateProvider<String, String> provider) {
        final var encoder = new JacksonEncodingStrategy<CommandState>(){};
        return new CommandStateStoreImpl(provider, encoder, config.stateTtl());
    }
}
