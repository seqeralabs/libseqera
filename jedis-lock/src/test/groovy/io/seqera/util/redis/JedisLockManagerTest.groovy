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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException

import redis.clients.jedis.JedisPool
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Timeout(60)
class JedisLockManagerTest extends Specification implements BaseRedisTest {

    @Shared
    JedisPool pool

    def setup() {
        pool = getJedisPool()
    }

    def cleanup() {
        pool?.close()
    }

    def 'should try acquire a lock' () {
        def key = UUID.randomUUID().toString()
        def conn = pool.getResource()
        def manager = new JedisLockManager(conn)

        when:
        def lock1 = manager.tryAcquire(key)
        then:
        lock1 != null
        then:
        !manager.tryAcquire(key)
        !manager.tryAcquire(key)

        when:
        lock1.release()
        then:
        def lock2 = manager.tryAcquire(key)
        and:
        lock2 != null
        then:
        !manager.tryAcquire(key)
        !manager.tryAcquire(key)
        and:
        lock2.release()

        cleanup:
        conn?.close()
        pool?.close()
    }

    def 'should block until the lock is not acquired' () {
        given:
        def key = UUID.randomUUID().toString()
        def conn1 = pool.getResource()
        def conn2 = pool.getResource()
        def manager1 = new JedisLockManager(conn1)
        def manager2 = new JedisLockManager(conn1)
        and:
        def started = new CompletableFuture()
        def done = new CompletableFuture()
        def AWAIT = 400
        when:
        def lock1 = manager1.acquire(key, null)
        and:
        Thread.start {
            // signal the thread started
            started.complete('x')
            // this acquire should block until the lock in the main thread is released
            final now = System.currentTimeMillis()
            def lock2 = manager2.acquire(key, null)
            def delta = System.currentTimeMillis()-now
            // simulate some work
            sleep 100
            // release the lock
            lock2.release()
            // signal completion
            done.complete(delta)
        }
        and:
        // await the thread started
        started.join()
        // spend some time - the lock 'acquire' in the thread should block all this time
        sleep AWAIT
        then:
        // release the lock - it should return true
        lock1.release()

        when:
        // await the thread to complete
        def elapsed = (long)done.get()
        then:
        // the time elapsed to acquire the lock in the thread should be larger than 'await'
        elapsed>=AWAIT

        cleanup:
        conn1?.close()
        conn2?.close()
    }

    def 'should timeout on acquire' () {
        given:
        def key = UUID.randomUUID().toString()
        def conn = pool.getResource()
        def manager = new JedisLockManager(conn)

        when:
        def lock = manager.acquire(key, null)
        and:
        manager.acquire(key, Duration.ofSeconds(1))
        then:
        thrown(TimeoutException)

        cleanup:
        lock?.release()
    }


    def 'should not deadlock if an acquiring instance dies'() {
        given:
        def key = UUID.randomUUID().toString()
        def conn1 = pool.getResource()
        def conn2 = pool.getResource()
        def manager1 = new JedisLockManager(conn1).withLockAutoExpireDuration(Duration.ofMillis(200)).withAcquireRetryInterval(Duration.ofMillis(10))
        def manager2 = new JedisLockManager(conn2).withLockAutoExpireDuration(Duration.ofMillis(200)).withAcquireRetryInterval(Duration.ofMillis(10))
        def JedisLock lock1
        def JedisLock lock2

        def latch = new CountDownLatch(1)
        when:
        Thread.start {
            lock1 = manager1.acquire(key, null)
            latch.countDown()
            // acquire but never release to simulate instance death
        }
        and: 'we acquire lock2 once the first lock has been acquired'
        latch.await()
        def start = System.currentTimeMillis()
        lock2 = manager2.acquire(key, null)
        then:
        // the lock should be acquired by the second instance
        conn2.get(key) == lock2.instanceId
        and:
        System.currentTimeMillis()-start>=200

        cleanup:
        lock1?.release()
        lock2?.release()
        conn1?.close()
        conn2?.close()
    }
}
