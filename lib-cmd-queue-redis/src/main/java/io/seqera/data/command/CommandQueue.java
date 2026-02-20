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

import io.seqera.data.stream.AbstractMessageStream;
import io.seqera.data.stream.MessageConsumer;
import io.seqera.data.stream.MessageStream;
import io.seqera.serde.encode.StringEncodingStrategy;
import io.seqera.serde.moshi.MoshiEncodeStrategy;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract message queue for command processing.
 * Extends AbstractMessageStream to provide async, fire-and-forget command submission.
 *
 * Subclasses must implement {@link #name()} and {@link #pollInterval()}
 * to configure the queue behavior.
 */
public abstract class CommandQueue extends AbstractMessageStream<CommandMsg> {

    private static final Logger log = LoggerFactory.getLogger(CommandQueue.class);

    public CommandQueue(MessageStream<String> target) {
        super(target);
        log.info("Created command queue - name={}", name());
    }

    @Override
    protected StringEncodingStrategy<CommandMsg> createEncodingStrategy() {
        return new MoshiEncodeStrategy<>() {};
    }

    /**
     * The name of the command queue. Used for logging and stream name derivation.
     */
    @Override
    protected abstract String name();

    /**
     * The name of the message stream, derived from {@link #name()}.
     */
    protected String streamName() {
        return name() + "/v1";
    }

    /**
     * Submit a command to the queue.
     *
     * @param msg the command message to queue
     */
    public void submit(CommandMsg msg) {
        offer(streamName(), msg);
    }

    /**
     * Register a consumer for commands.
     *
     * @param consumer the consumer to process commands
     */
    public void addConsumer(MessageConsumer<CommandMsg> consumer) {
        addConsumer(streamName(), consumer);
    }

    /**
     * Get the current queue length.
     *
     * @return number of pending commands
     */
    public int length() {
        return length(streamName());
    }

    @PreDestroy
    void destroy() {
        log.debug("Shutting down command queue");
        close();
    }
}
