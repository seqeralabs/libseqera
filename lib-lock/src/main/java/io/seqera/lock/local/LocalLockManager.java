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

package io.seqera.lock.local;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import io.seqera.lock.Lock;
import io.seqera.lock.LockConfig;
import io.seqera.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@link LockManager} for local development and testing.
 *
 * Uses {@link ConcurrentHashMap} for thread-safe lock operations within a single JVM.
 * Note: This implementation does NOT provide distributed locking across multiple processes.
 *
 * Created by {@link LocalLockManagerFactory} for each named lock configuration.
 *
 * @author Paolo Di Tommaso
 */
public class LocalLockManager implements LockManager {

    private static final Logger log = LoggerFactory.getLogger(LocalLockManager.class);

    private final LockConfig config;
    private final ConcurrentHashMap<String, String> locks = new ConcurrentHashMap<>();

    public LocalLockManager(LockConfig config) {
        this.config = config;
        log.info("Creating LocalLockManager: {}", config);
    }

    @Override
    public Lock tryAcquire(String lockKey) {
        final String instanceId = UUID.randomUUID().toString();
        if (locks.putIfAbsent(lockKey, instanceId) == null) {
            log.trace("Lock acquired: lockKey={}, instanceId={}", lockKey, instanceId);
            return new LocalLock(this, lockKey, instanceId);
        }
        log.trace("Lock not available: lockKey={}", lockKey);
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
            long delta = System.currentTimeMillis() - start;
            if (timeoutMs > 0 && delta > timeoutMs) {
                throw new TimeoutException("Lock '" + lockKey + "' timed out after " + Duration.ofMillis(delta));
            }
            try {
                Thread.sleep(config.getAcquireRetryInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Lock acquisition interrupted for: " + lockKey);
            }
        }
    }

    /**
     * Release a lock. Called by {@link LocalLock#release()}.
     *
     * @param lockKey The lock key
     * @param instanceId The instance that holds the lock
     * @return {@code true} if released, {@code false} if held by another instance
     */
    boolean release(String lockKey, String instanceId) {
        boolean released = locks.remove(lockKey, instanceId);
        if (released) {
            log.trace("Lock released: lockKey={}, instanceId={}", lockKey, instanceId);
        } else {
            log.trace("Lock release failed (different owner): lockKey={}", lockKey);
        }
        return released;
    }
}
