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

package io.seqera.lock.redis;

import java.util.Collections;

import io.seqera.lock.Lock;
import redis.clients.jedis.JedisPool;

/**
 * Redis-based implementation of {@link Lock}.
 *
 * Uses a Lua script to atomically check ownership and release the lock.
 *
 * @author Paolo Di Tommaso
 */
public class RedisLock implements Lock {

    private static final String RELEASE_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('DEL', KEYS[1]) " +
        "else " +
        "    return 0 " +
        "end";

    private final JedisPool pool;
    private final String lockKey;
    private final String instanceId;

    RedisLock(JedisPool pool, String lockKey, String instanceId) {
        this.pool = pool;
        this.lockKey = lockKey;
        this.instanceId = instanceId;
    }

    @Override
    public boolean release() {
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(RELEASE_SCRIPT,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(instanceId));
            return Long.valueOf(1L).equals(result);
        }
    }
}