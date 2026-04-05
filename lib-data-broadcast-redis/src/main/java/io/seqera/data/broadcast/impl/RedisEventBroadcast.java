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

package io.seqera.data.broadcast.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.seqera.data.broadcast.EventBroadcast;
import io.seqera.serde.encode.StringEncodingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Redis Streams-backed implementation of {@link EventBroadcast}.
 *
 * <p>Uses Redis Streams for persistent event storage across replicas:
 * <ul>
 *   <li>{@code XADD} to append events</li>
 *   <li>{@code XRANGE} to replay buffered events for late-connecting clients</li>
 * </ul>
 *
 * <p>Does NOT use consumer groups — all clients receive all events (broadcast semantics).
 * Local client push delivery is handled in-memory; Redis provides the durable buffer
 * that survives restarts and is shared across replicas.
 *
 * @param <T> the event type
 * @author Paolo Di Tommaso
 */
public class RedisEventBroadcast<T> implements EventBroadcast<T> {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBroadcast.class);

    private static final String DATA_FIELD = "data";

    private final JedisPool pool;
    private final String prefix;
    private final StringEncodingStrategy<T> encoding;

    /** Local client registry for push delivery on this instance. */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Consumer<T>>> clients = new ConcurrentHashMap<>();

    /**
     * @param pool     Jedis connection pool
     * @param prefix   Redis key prefix (e.g. "sched:agent-events:")
     * @param encoding strategy for encoding/decoding events to/from strings
     */
    public RedisEventBroadcast(JedisPool pool, String prefix, StringEncodingStrategy<T> encoding) {
        this.pool = pool;
        this.prefix = prefix;
        this.encoding = encoding;
    }

    private String streamKey(String key) {
        return prefix + key;
    }

    @Override
    public void offer(String key, T event) {
        final String encoded = encoding.encode(event);
        final Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // Persist to Redis Stream
            try (Jedis jedis = pool.getResource()) {
                jedis.xadd(streamKey(key), StreamEntryID.NEW_ENTRY, Map.of(DATA_FIELD, encoded));
            }

            // Push to local clients on this instance
            final var keyClients = clients.get(key);
            if (keyClients == null)
                return;
            for (var entry : keyClients.entrySet()) {
                try {
                    entry.getValue().accept(event);
                } catch (Exception e) {
                    log.warn("Failed to deliver event to client {} for key {}: {}", entry.getKey(), key, e.getMessage());
                    keyClients.remove(entry.getKey());
                }
            }
        }
    }

    @Override
    public void registerClient(String key, String clientId, Consumer<T> callback) {
        final Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            // Replay all events from Redis Stream
            try (Jedis jedis = pool.getResource()) {
                final List<StreamEntry> entries = jedis.xrange(streamKey(key), (StreamEntryID) null, (StreamEntryID) null);
                if (entries != null) {
                    for (StreamEntry entry : entries) {
                        final String encoded = entry.getFields().get(DATA_FIELD);
                        if (encoded != null) {
                            try {
                                callback.accept(encoding.decode(encoded));
                            } catch (Exception e) {
                                log.warn("Failed to deliver buffered event to client {} for key {}: {}", clientId, key, e.getMessage());
                                break;
                            }
                        }
                    }
                }
            }

            // Register for future push delivery
            clients.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(clientId, callback);
        }
    }

    @Override
    public void unregisterClient(String key, String clientId) {
        final var keyClients = clients.get(key);
        if (keyClients != null) {
            keyClients.remove(clientId);
            if (keyClients.isEmpty()) {
                clients.remove(key);
            }
        }
    }

    @Override
    public List<T> getBufferedEvents(String key) {
        try (Jedis jedis = pool.getResource()) {
            final List<StreamEntry> entries = jedis.xrange(streamKey(key), (StreamEntryID) null, (StreamEntryID) null);
            if (entries == null)
                return List.of();
            final var result = new ArrayList<T>(entries.size());
            for (StreamEntry entry : entries) {
                final String encoded = entry.getFields().get(DATA_FIELD);
                if (encoded != null) {
                    result.add(encoding.decode(encoded));
                }
            }
            return result;
        }
    }

    @Override
    public void cleanup(String key) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(streamKey(key));
        }
        clients.remove(key);
        locks.remove(key);
    }
}
