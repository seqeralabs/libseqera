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

package io.seqera.util.redis

import java.time.Duration

import groovy.transform.PackageScope

/**
 * Hold Redis/Jedis lock acquired with {@link JedisLockManager}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class JedisLock implements AutoCloseable {

    final private JedisLockManager manager

    final private String lockKey

    final String instanceId

    @PackageScope
    JedisLock(JedisLockManager manager, String lockKey, String instanceId) {
        this.manager = manager
        this.lockKey = lockKey
        this.instanceId = instanceId
    }

    /**
     * Release the lock instance.
     *
     * See also {@link JedisLockManager#acquire(String, Duration)}
     *
     * @return
     *      {@code true} if the lock has been released correctly, or {@code false} if the lock cannot be released
     *      because is hold by another instance
     */
    boolean release() {
        return manager.release(lockKey, instanceId)
    }

    /**
     * Close the lock by invoking #release method
     */
    @Override
    void close() throws Exception {
        release()
    }
}
