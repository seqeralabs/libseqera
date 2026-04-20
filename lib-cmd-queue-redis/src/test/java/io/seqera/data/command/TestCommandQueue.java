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

import java.time.Duration;
import java.util.concurrent.ExecutorService;

import io.micronaut.context.annotation.Factory;
import io.micronaut.scheduling.TaskExecutors;
import io.seqera.data.command.store.CommandStateStore;
import io.seqera.data.stream.MessageStream;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Test implementation of CommandQueue for testing.
 */
class TestCommandQueue extends CommandQueue {

    TestCommandQueue(MessageStream<String> target) {
        super(target);
    }

    @Override
    protected String name() {
        return "test-command-queue";
    }

    @Override
    protected Duration pollInterval() {
        return Duration.ofMillis(100);
    }
}

/**
 * Factory producing the test {@link CommandQueue} and its accompanying
 * {@link CommandService}. The library no longer exposes a default
 * {@code @Singleton CommandServiceImpl} bean; each consumer wires its own.
 */
@Factory
class TestCommandQueueFactory {

    @Singleton
    CommandQueue commandQueue(MessageStream<String> target) {
        return new TestCommandQueue(target);
    }

    @Singleton
    CommandService commandService(
            CommandConfig config,
            CommandStateStore store,
            CommandQueue queue,
            @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        return new CommandServiceImpl(config, store, queue, executor);
    }
}
