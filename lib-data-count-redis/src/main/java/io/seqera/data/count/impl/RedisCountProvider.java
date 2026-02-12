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

package io.seqera.data.count.impl;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis based implementation for a distributed counter
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(bean = RedisActivator.class)
@Singleton
public class RedisCountProvider implements CountProvider {

    @Inject
    private JedisPool pool;

    @Override
    public long increment(String key, long value) {
        try (Jedis conn = pool.getResource()) {
            return conn.incrBy(key, value);
        }
    }

    @Override
    public long decrement(String key, long value) {
        try (Jedis conn = pool.getResource()) {
            return conn.decrBy(key, value);
        }
    }

    @Override
    public long get(String key) {
        try (Jedis conn = pool.getResource()) {
            String result = conn.get(key);
            return result != null ? Long.parseLong(result) : 0;
        }
    }

    @Override
    public void clear(String key) {
        try (Jedis conn = pool.getResource()) {
            conn.del(key);
        }
    }
}
