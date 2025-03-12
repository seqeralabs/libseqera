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
