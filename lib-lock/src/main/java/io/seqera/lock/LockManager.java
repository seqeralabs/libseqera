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

package io.seqera.lock;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Factory interface for acquiring distributed locks.
 *
 * Implementations provide different backends (local in-memory, Redis, etc.)
 * and are selected via Micronaut's conditional bean activation.
 *
 * @author Paolo Di Tommaso
 */
public interface LockManager {

    /**
     * Try to acquire a lock without blocking.
     *
     * @param lockKey The unique key identifying the lock
     * @return A {@link Lock} instance if acquired, {@code null} if the lock is held by another process
     */
    Lock tryAcquire(String lockKey);

    /**
     * Acquire a lock, blocking until available or timeout.
     *
     * @param lockKey The unique key identifying the lock
     * @param timeout Maximum time to wait for the lock
     * @return A {@link Lock} instance
     * @throws TimeoutException if the lock cannot be acquired within the timeout
     */
    Lock acquire(String lockKey, Duration timeout) throws TimeoutException;
}
