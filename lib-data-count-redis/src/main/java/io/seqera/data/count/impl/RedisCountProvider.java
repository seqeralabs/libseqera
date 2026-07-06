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

package io.seqera.data.count.impl;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisNoScriptException;

/**
 * Redis based implementation for a distributed counter
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(bean = RedisActivator.class)
@Singleton
public class RedisCountProvider implements CountProvider {

    /**
     * Atomic bounded check-and-increment. INCRBY the key by the requested value; if the new total
     * exceeds the limit, undo the increment and return 0 (rejected), otherwise return 1 (admitted).
     * A first increment (current == value) sets the TTL so freshly-created keys always expire.
     * KEYS[1]=key; ARGV[1]=value; ARGV[2]=limit; ARGV[3]=ttl seconds.
     */
    private static final String TRY_ACQUIRE_LUA = """
            local current = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
            if current == tonumber(ARGV[1]) and tonumber(ARGV[3]) > 0 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            end
            if current > tonumber(ARGV[2]) then
              redis.call('DECRBY', KEYS[1], tonumber(ARGV[1]))
              return 0
            end
            return 1
            """;

    /** Cached SHA1 of {@link #TRY_ACQUIRE_LUA}, lazily loaded so we EVALSHA instead of shipping the
     *  script body on every call. Reloaded transparently if Redis reports NOSCRIPT (e.g. after a
     *  server restart or SCRIPT FLUSH). */
    private final AtomicReference<String> scriptSha = new AtomicReference<>();

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

    @Override
    public boolean tryAcquire(String key, long value, long limit, long ttlSeconds) {
        if (value < 0)
            throw new IllegalArgumentException("tryAcquire value must be non-negative, got " + value);
        if (limit < 0)
            throw new IllegalArgumentException("tryAcquire limit must be non-negative, got " + limit);
        final List<String> keys = List.of(key);
        final List<String> args = List.of(Long.toString(value), Long.toString(limit), Long.toString(ttlSeconds));
        try (Jedis conn = pool.getResource()) {
            // Prefer EVALSHA (script cached server-side) to avoid shipping the script body each call;
            // load-and-cache the SHA on first use, and transparently reload if Redis has forgotten it.
            String sha = scriptSha.get();
            if (sha == null) {
                sha = conn.scriptLoad(TRY_ACQUIRE_LUA);
                scriptSha.set(sha);
            }
            try {
                return admitted(conn.evalsha(sha, keys, args));
            }
            catch (JedisNoScriptException e) {
                // Script evicted server-side (restart / SCRIPT FLUSH) — reload and retry once.
                sha = conn.scriptLoad(TRY_ACQUIRE_LUA);
                scriptSha.set(sha);
                return admitted(conn.evalsha(sha, keys, args));
            }
        }
    }

    private static boolean admitted(Object result) {
        return result instanceof Long r && r == 1L;
    }
}
