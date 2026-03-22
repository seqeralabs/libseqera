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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micronaut.scheduling.TaskScheduler;
import io.seqera.lock.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Redis-based implementation of {@link Lock}.
 *
 * Uses Lua scripts for atomic ownership checks on release and TTL renewal.
 * Optionally runs a watchdog timer that periodically renews the lock TTL,
 * preventing expiration while the holder is alive.
 *
 * @author Paolo Di Tommaso
 */
public class RedisLock implements Lock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    /**
     * Lua script: atomically delete the key only if the caller owns it.
     */
    private static final String RELEASE_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('DEL', KEYS[1]) " +
        "else " +
        "    return 0 " +
        "end";

    /**
     * Lua script: atomically renew the TTL only if the caller owns the key.
     * ARGV[1] = instanceId, ARGV[2] = new TTL in milliseconds.
     */
    private static final String RENEW_SCRIPT =
        "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
        "    return redis.call('PEXPIRE', KEYS[1], ARGV[2]) " +
        "else " +
        "    return 0 " +
        "end";

    private final JedisPool pool;
    private final String lockKey;
    private final String instanceId;
    private final AtomicBoolean released = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> watchdogFuture;

    RedisLock(JedisPool pool, String lockKey, String instanceId) {
        this.pool = pool;
        this.lockKey = lockKey;
        this.instanceId = instanceId;
    }

    /**
     * Start the watchdog timer that renews the lock TTL at interval = ttl / 3.
     *
     * @param ttl the lock's TTL duration
     * @param scheduler the Micronaut task scheduler
     */
    void startWatchdog(Duration ttl, TaskScheduler scheduler) {
        final long ttlMs = ttl.toMillis();
        final Duration renewInterval = Duration.ofMillis(ttlMs / 3);
        watchdogFuture = scheduler.scheduleAtFixedRate(
                renewInterval,
                renewInterval,
                () -> renew(ttlMs));
        log.trace("Watchdog started for lockKey={}, renewInterval={}", lockKey, renewInterval);
    }

    private void renew(long ttlMs) {
        if (released.get()) return;
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(RENEW_SCRIPT,
                    Collections.singletonList(lockKey),
                    List.of(instanceId, String.valueOf(ttlMs)));
            if (Long.valueOf(1L).equals(result)) {
                log.trace("Watchdog renewed lock: lockKey={}", lockKey);
            } else {
                log.warn("Watchdog renewal failed (lock lost): lockKey={}", lockKey);
                stopWatchdog();
            }
        } catch (Exception e) {
            log.warn("Watchdog renewal error for lockKey={}: {}", lockKey, e.getMessage());
        }
    }

    private void stopWatchdog() {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(false);
            watchdogFuture = null;
        }
    }

    @Override
    public boolean release() {
        if (!released.compareAndSet(false, true))
            return false;
        stopWatchdog();
        try (var jedis = pool.getResource()) {
            Object result = jedis.eval(RELEASE_SCRIPT,
                    Collections.singletonList(lockKey),
                    Collections.singletonList(instanceId));
            return Long.valueOf(1L).equals(result);
        }
    }
}
