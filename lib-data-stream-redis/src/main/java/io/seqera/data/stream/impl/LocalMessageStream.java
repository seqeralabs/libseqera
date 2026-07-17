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

package io.seqera.data.stream.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.data.stream.MessageStream;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@link MessageStream} using Java {@link LinkedBlockingQueue}
 * as the underlying storage mechanism. This implementation is designed exclusively for
 * development, testing, and local environments.
 *
 * <p><strong>Important:</strong> This implementation should <b>never</b> be used in production
 * environments as it provides no persistence, durability, or distribution capabilities.
 * Messages are stored only in local JVM memory and will be lost on application restart.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li><b>Local Only:</b> Messages exist only within the current JVM instance</li>
 *   <li><b>No Persistence:</b> All messages are lost when the application stops</li>
 *   <li><b>No Distribution:</b> Cannot share messages across multiple application instances</li>
 *   <li><b>Simple Queuing:</b> Messages are processed in FIFO order using blocking queues</li>
 *   <li><b>Retry Logic:</b> Failed messages are re-queued after a 1-second delay</li>
 * </ul>
 *
 * <p>This implementation automatically activates when the 'redis' environment is <b>not</b>
 * active, making it ideal for:
 * <ul>
 *   <li>Local development without Redis infrastructure</li>
 *   <li>Unit testing scenarios</li>
 *   <li>Quick prototyping and experimentation</li>
 * </ul>
 *
 * <p>Each stream is backed by its own {@link ConcurrentHashMap} entry containing
 * a {@link LinkedBlockingQueue} for thread-safe message handling.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
@Requires(missingBeans = RedisActivator.class)
@Singleton
public class LocalMessageStream implements MessageStream<String> {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageStream.class);

    private final ConcurrentHashMap<String, LinkedBlockingQueue<String>> delegate = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(String streamId) {
        delegate.put(streamId, new LinkedBlockingQueue<>());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void offer(String streamId, String message) {
        delegate
                .get(streamId)
                .offer(message);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads one message off the local queue. There is no pending-entries list, so
     * the lease id is simply the message value itself (used to re-offer it on release).
     */
    @Override
    public Lease<String> poll(String streamId) {
        final var message = delegate
                .get(streamId)
                .poll();
        if (message == null) {
            return null;
        }
        return new Lease<>(message, message);
    }

    /**
     * {@inheritDoc}
     *
     * <p>No pending-entries list ⇒ no lease semantics ⇒ no-op.
     */
    @Override
    public void renew(String streamId, String leaseId) {
        // no-op: the local queue has no pending-entries list
    }

    /**
     * {@inheritDoc}
     *
     * <p>The message was already removed from the queue on {@link #poll(String)},
     * so acknowledgment is a no-op (the entry is simply dropped).
     */
    @Override
    public void ack(String streamId, String leaseId) {
        // no-op: the entry was removed from the queue on poll
    }

    /**
     * {@inheritDoc}
     *
     * <p>Re-offers the message onto the queue so it is redelivered later, mimicking the
     * behavior of a Redis stream pending entry that is not acknowledged.
     */
    @Override
    public void release(String streamId, String leaseId) {
        offer(streamId, leaseId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int length(String streamId) {
        return delegate.get(streamId).size();
    }
}
