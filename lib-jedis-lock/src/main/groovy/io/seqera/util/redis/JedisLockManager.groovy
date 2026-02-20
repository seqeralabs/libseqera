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

package io.seqera.util.redis

import java.time.Duration
import java.util.concurrent.TimeoutException

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import redis.clients.jedis.Jedis
import redis.clients.jedis.params.SetParams

/**
 * Implement a Redis lock.
 *
 * NOTE:
 *  - This only works for single instance cluster.
 *
 *  - This implementation is a simplified version of the algorithm described
 *    at this page https://redis.io/docs/latest/develop/use/patterns/distributed-locks/
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class JedisLockManager {

    private static final Duration DEFAULT_ACQUIRE_RETRY_INTERVAL = Duration.ofMillis(100)
    private static final Duration DEFAULT_LOCK_AUTO_EXPIRE_DURATION = Duration.ofMinutes(5)

    private final Jedis conn
    private long lockAutoExpireDuration = DEFAULT_LOCK_AUTO_EXPIRE_DURATION.toMillis()
    private long acquireRetryInterval = DEFAULT_ACQUIRE_RETRY_INTERVAL.toMillis()

    /**
     * Creates a lock manager instance for the given {@link Jedis} connection object
     *
     * @param conn The {@link Jedis} object holding a the connection with Redis
     */
    JedisLockManager(Jedis conn) {
        assert conn, "Jedis connection cannot be null"
        this.conn = conn
    }

    JedisLockManager withLockAutoExpireDuration(Duration lockAutoExpireDuration) {
        assert lockAutoExpireDuration && lockAutoExpireDuration != Duration.ZERO, "Lock auto-expire duration cannot be null"
        this.lockAutoExpireDuration = lockAutoExpireDuration.toMillis()
        return this
    }

    JedisLockManager withAcquireRetryInterval(Duration acquireRetryInterval) {
        assert acquireRetryInterval && acquireRetryInterval != Duration.ZERO, "Acquire retry interval cannot be null"
        this.acquireRetryInterval = acquireRetryInterval.toMillis()
        return this
    }

    /**
     * Try to acquire a lock for the given key
     *
     * @param lockKey
     *      The key representing the lock to acquired
     * @return
     *      A {@link JedisLock} object if the lock has been acquired successfully or {@code null} otherwise
     */
    JedisLock tryAcquire(String lockKey) {
        final instanceId = UUID.randomUUID().toString()
        // the `NX` param set the key only if it does not exist and PX to protect from deadlocks
        final ret = conn.set(lockKey, instanceId, new SetParams().nx().px(lockAutoExpireDuration))
        return ret=="OK"
                ? new JedisLock(this, lockKey, instanceId)
                : null
    }

    /**
     * Acquire a Redis lock for the given key, awai
     *
     * @param lockKey
     *      The key representing the lock to acquired
     * @param timeout
     *      How long it can block to acquire the lock. When {@code null} await forever
     * @return
     *      The {@link JedisLock} for the given key
     * @throws TimeoutException
     *      Thrown when the lock cannot be acquired within the given timeout
     */
    JedisLock acquire(String lockKey, Duration timeout) throws TimeoutException {
        final timeoutMs = timeout ? timeout.toMillis() : 0
        final start = System.currentTimeMillis()
        while( true ) {
            final lock = tryAcquire(lockKey)
            if( lock )
                return lock
            final delta = System.currentTimeMillis()-start
            if( timeoutMs && delta > timeoutMs )
                throw new TimeoutException("Redis lock '$lockKey' timed out after ${Duration.ofMillis(delta)}")
            Thread.sleep(acquireRetryInterval)
        }
    }

    /**
     * Release a given lock. This method is not meant to be invoked directly, but via {@link JedisLock}
     *
     * @param lockKey
     *      The lock key
     * @param instanceId
     *      Unique Id representing the invoking client
     * @return
     *      {@code true} if the lock has been released correctly, or {@code false} if the lock cannot be released
     *      because is hold by another instance
     */
    @PackageScope
    boolean release(String lockKey, String instanceId) {
        //language=lua
        final script = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                -- lock is held by another instance
                return 0
            end
        """
        // we can't simply delete here, because the lock might be held by a different instance
        conn.eval(script, 1, lockKey, instanceId) == 1L
    }

}
