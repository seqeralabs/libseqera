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

package io.seqera.nodeid;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Redis-backed {@link NodeId} that assigns ordinals from a shared, atomically-incremented
 * counter, rotated modulo {@code capacity}.
 *
 * <p>On startup each replica runs a small atomic Lua script that increments a shared counter
 * key and returns the result (zero-based) modulo capacity. The script also wraps the stored
 * value back into {@code [0, capacity)} so the counter never grows toward the 64-bit
 * {@code INCR} overflow. Consecutively-started replicas therefore receive consecutive,
 * distinct ordinals — the common case under rolling deployments. If Redis is unreachable the
 * replica falls back to a random ordinal so that startup never blocks.
 *
 * <p>The counter key is namespaced by the Micronaut application name, so distinct apps
 * sharing a Redis instance never contend on the same counter.
 *
 * <p>Activated when a {@link RedisActivator} bean is present.
 *
 * @author Paolo Di Tommaso
 */
@Singleton
@Requires(bean = RedisActivator.class)
public class RedisNodeId implements NodeId {

    private static final Logger log = LoggerFactory.getLogger(RedisNodeId.class);

    /**
     * Atomically increment the counter and return the next ordinal in {@code [0, capacity)}.
     * The stored value is wrapped back into range once it reaches capacity, so it never
     * grows without bound (avoiding 64-bit {@code INCR} overflow). ARGV[1] = capacity.
     */
    private static final String ASSIGN_SCRIPT =
            "local cap = tonumber(ARGV[1]) " +
            "local v = redis.call('INCR', KEYS[1]) " +
            "if v >= cap then redis.call('SET', KEYS[1], v % cap) end " +
            "return (v - 1) % cap";

    private final int value;
    private final int capacity;

    @Inject
    public RedisNodeId(
            @Value("${micronaut.application.name}") String namespace,
            @Value("${seqera.node-id.capacity:1024}") int capacity,
            JedisPool pool) {
        this.capacity = capacity;
        this.value = assign(namespace, pool);
    }

    @Override
    public int value() {
        return value;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    private int assign(String namespace, JedisPool pool) {
        final String key = namespace + ":node-id:counter";
        try (var jedis = pool.getResource()) {
            final Object result = jedis.eval(ASSIGN_SCRIPT, List.of(key), List.of(String.valueOf(capacity)));
            final int ordinal = ((Long) result).intValue();
            log.info("Assigned node id: value={}, capacity={}, namespace={}", ordinal, capacity, namespace);
            return ordinal;
        } catch (Exception e) {
            final int ordinal = ThreadLocalRandom.current().nextInt(capacity);
            log.warn("Unable to assign node id from Redis (namespace={}); falling back to random value={}: {}",
                    namespace, ordinal, e.getMessage());
            return ordinal;
        }
    }
}
