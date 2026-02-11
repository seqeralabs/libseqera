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

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import io.seqera.lock.Lock;
import io.seqera.lock.LockConfig;
import io.seqera.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Redis-based implementation of {@link LockManager}.
 *
 * Uses Redis SET with NX and PX options for atomic lock acquisition.
 * Created by {@link RedisLockManagerFactory} for each named lock configuration.
 *
 * @author Paolo Di Tommaso
 */
public class RedisLockManager implements LockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisLockManager.class);

    private final LockConfig config;
    private final JedisPool jedisPool;

    public RedisLockManager(LockConfig config, JedisPool jedisPool) {
        this.config = config;
        this.jedisPool = jedisPool;
        log.info("Creating RedisLockManager: {}", config);
    }

    @Override
    public Lock tryAcquire(String lockKey) {
        final String instanceId = UUID.randomUUID().toString();
        try (var jedis = jedisPool.getResource()) {
            String result = jedis.set(lockKey, instanceId,
                    new SetParams().nx().px(config.getAutoExpireDuration().toMillis()));
            if ("OK".equals(result)) {
                log.trace("Redis lock acquired: lockKey={}", lockKey);
                return new RedisLock(jedisPool, lockKey, instanceId);
            }
        }
        log.trace("Redis lock not available: lockKey={}", lockKey);
        return null;
    }

    @Override
    public Lock acquire(String lockKey, Duration timeout) throws TimeoutException {
        final long timeoutMs = timeout != null ? timeout.toMillis() : 0;
        final long start = System.currentTimeMillis();
        while (true) {
            Lock lock = tryAcquire(lockKey);
            if (lock != null) {
                return lock;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (timeoutMs > 0 && elapsed > timeoutMs) {
                throw new TimeoutException("Redis lock '" + lockKey + "' timed out after " + Duration.ofMillis(elapsed));
            }
            try {
                Thread.sleep(config.getAcquireRetryInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Lock acquisition interrupted for: " + lockKey);
            }
        }
    }
}
