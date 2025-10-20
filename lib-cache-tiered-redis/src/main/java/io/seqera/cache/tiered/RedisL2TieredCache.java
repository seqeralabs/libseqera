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
 *
 */

package io.seqera.cache.tiered;

import java.time.Duration;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Redis-based implementation of the {@link L2TieredCache} interface.
 *
 * <p>This implementation provides a distributed cache layer using Redis as the
 * backing store. It is designed to work in distributed environments where multiple
 * application instances need to share cached data.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Uses Jedis connection pooling for efficient resource management</li>
 *   <li>Supports TTL-based expiration at the Redis level</li>
 *   <li>Thread-safe through Redis's atomic operations</li>
 *   <li>Automatically enabled when Redis infrastructure is available</li>
 * </ul>
 *
 * <p>This class is conditionally loaded based on the presence of a {@link RedisActivator} bean,
 * making it easy to disable Redis caching when Redis is not available.</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(bean = RedisActivator.class)
@Singleton
public class RedisL2TieredCache implements L2TieredCache<String, String> {

    @Inject
    private JedisPool pool;

    /**
     * Retrieves the value associated with the specified key from Redis.
     *
     * @param key the key whose associated value is to be returned
     * @return the value associated with the key, or {@code null} if not found or expired
     */
    @Override
    public String get(String key) {
        try (Jedis conn = pool.getResource()) {
            return conn.get(key);
        }
    }

    /**
     * Stores a value in Redis with the specified key and time-to-live.
     *
     * <p>If the key already exists, its value will be overwritten. The TTL is
     * set at the Redis level, so the value will automatically expire after the
     * specified duration.</p>
     *
     * @param key the key with which the specified value is to be associated
     * @param value the value to be associated with the specified key
     * @param ttl the time-to-live duration; if null, no expiration is set
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        try (Jedis conn = pool.getResource()) {
            final SetParams params = new SetParams();
            if (ttl != null) {
                params.px(ttl.toMillis());
            }
            conn.set(key, value, params);
        }
    }
}
