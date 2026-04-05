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

package io.seqera.data.broadcast;

import java.util.List;
import java.util.function.Consumer;

import io.micronaut.core.annotation.Nullable;
import io.seqera.data.broadcast.impl.LocalEventBroadcast;
import io.seqera.data.broadcast.impl.RedisEventBroadcast;
import io.seqera.serde.encode.StringEncodingStrategy;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Base class for typed event broadcasts. Delegates to either
 * {@link LocalEventBroadcast} (single-replica) or {@link RedisEventBroadcast} (multi-replica)
 * depending on whether a {@link JedisPool} is available in the application context.
 *
 * <p>Concrete subclasses provide the Redis key prefix and encoding strategy.
 * The {@link JedisPool} is optionally injected by Micronaut — when absent,
 * the local in-memory implementation is used automatically.
 *
 * @param <T> the event type
 * @author Paolo Di Tommaso
 */
public abstract class AbstractEventBroadcast<T> implements EventBroadcast<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractEventBroadcast.class);

    private EventBroadcast<T> delegate;

    @Inject @Nullable private JedisPool jedisPool;

    private final String prefix;
    private final StringEncodingStrategy<T> encoding;

    /**
     * @param prefix   Redis key prefix (e.g. "sched:agent-events:")
     * @param encoding strategy for encoding/decoding events to/from strings
     */
    protected AbstractEventBroadcast(String prefix, StringEncodingStrategy<T> encoding) {
        this.prefix = prefix;
        this.encoding = encoding;
    }

    private EventBroadcast<T> delegate() {
        if (delegate == null) {
            if (jedisPool != null) {
                log.info("Using Redis-backed event broadcast with prefix '{}'", prefix);
                delegate = new RedisEventBroadcast<>(jedisPool, prefix, encoding);
            } else {
                log.info("Using local in-memory event broadcast");
                delegate = new LocalEventBroadcast<>();
            }
        }
        return delegate;
    }

    @Override
    public void offer(String key, T event) {
        delegate().offer(key, event);
    }

    @Override
    public void registerClient(String key, String clientId, Consumer<T> callback) {
        delegate().registerClient(key, clientId, callback);
    }

    @Override
    public void unregisterClient(String key, String clientId) {
        delegate().unregisterClient(key, clientId);
    }

    @Override
    public List<T> getBufferedEvents(String key) {
        return delegate().getBufferedEvents(key);
    }

    @Override
    public void cleanup(String key) {
        delegate().cleanup(key);
    }
}
