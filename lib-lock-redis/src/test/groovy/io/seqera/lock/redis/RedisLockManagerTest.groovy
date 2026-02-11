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

package io.seqera.lock.redis

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import io.seqera.lock.LockConfig
import io.seqera.util.redis.BaseRedisTest
import redis.clients.jedis.JedisPool
import spock.lang.Specification

class RedisLockManagerTest extends Specification implements BaseRedisTest {

    RedisLockManager createManager(JedisPool pool) {
        def config = new LockConfig('test')
        config.setAutoExpireDuration(Duration.ofMinutes(1))
        config.setAcquireRetryInterval(Duration.ofMillis(50))
        return new RedisLockManager(config, pool)
    }

    def 'should acquire and release lock'() {
        given:
        def manager = createManager(jedisPool)

        when:
        def lock = manager.tryAcquire('test-lock-1')

        then:
        lock != null

        when:
        def released = lock.release()

        then:
        released
    }

    def 'should fail to acquire lock held by another'() {
        given:
        def manager = createManager(jedisPool)

        when:
        def lock1 = manager.tryAcquire('test-lock-2')
        def lock2 = manager.tryAcquire('test-lock-2')

        then:
        lock1 != null
        lock2 == null

        cleanup:
        lock1?.release()
    }

    def 'should acquire lock after release'() {
        given:
        def manager = createManager(jedisPool)

        when:
        def lock1 = manager.tryAcquire('test-lock-3')
        lock1.release()
        def lock2 = manager.tryAcquire('test-lock-3')

        then:
        lock2 != null

        cleanup:
        lock2?.release()
    }

    def 'should support multiple independent locks'() {
        given:
        def manager = createManager(jedisPool)

        when:
        def lock1 = manager.tryAcquire('redis-lock-1')
        def lock2 = manager.tryAcquire('redis-lock-2')

        then:
        lock1 != null
        lock2 != null

        cleanup:
        lock1?.release()
        lock2?.release()
    }

    def 'should block and acquire lock with timeout'() {
        given:
        def manager = createManager(jedisPool)

        when:
        def lock = manager.acquire('test-lock-4', Duration.ofSeconds(5))

        then:
        lock != null

        cleanup:
        lock?.release()
    }

    def 'should timeout when lock not available'() {
        given:
        def manager = createManager(jedisPool)
        def lock1 = manager.tryAcquire('test-lock-5')

        when:
        manager.acquire('test-lock-5', Duration.ofMillis(200))

        then:
        thrown(TimeoutException)

        cleanup:
        lock1?.release()
    }

    def 'should wait and acquire lock when released'() {
        given:
        def manager = createManager(jedisPool)
        def lock1 = manager.tryAcquire('test-lock-6')
        def acquiredLatch = new CountDownLatch(1)
        def lock2Holder = new Object[1]

        when:
        Thread.start {
            lock2Holder[0] = manager.acquire('test-lock-6', Duration.ofSeconds(5))
            acquiredLatch.countDown()
        }
        Thread.sleep(100)
        lock1.release()
        acquiredLatch.await()

        then:
        lock2Holder[0] != null

        cleanup:
        lock2Holder[0]?.release()
    }

    def 'should work with try-with-resources'() {
        given:
        def manager = createManager(jedisPool)

        when:
        manager.tryAcquire('test-lock-7').withCloseable { acquired ->
            // Lock should be held here
            assert manager.tryAcquire('test-lock-7') == null
        }
        // Lock should be released after block
        def newLock = manager.tryAcquire('test-lock-7')

        then:
        newLock != null

        cleanup:
        newLock?.release()
    }

    def 'should handle concurrent lock attempts'() {
        given:
        def manager = createManager(jedisPool)
        def successCount = new AtomicInteger(0)
        def threadCount = 10
        def latch = new CountDownLatch(threadCount)

        when:
        (1..threadCount).each {
            Thread.start {
                def lock = manager.tryAcquire('test-lock-8')
                if (lock != null) {
                    successCount.incrementAndGet()
                    Thread.sleep(50)
                    lock.release()
                }
                latch.countDown()
            }
        }
        latch.await()

        then:
        successCount.get() == 1  // Only one thread should acquire the lock
    }

    def 'should work across multiple manager instances'() {
        given:
        def manager1 = createManager(jedisPool)
        def manager2 = createManager(jedisPool)

        when:
        def lock1 = manager1.tryAcquire('test-lock-9')
        def lock2 = manager2.tryAcquire('test-lock-9')

        then:
        lock1 != null
        lock2 == null  // Same key, different manager - should fail

        cleanup:
        lock1?.release()
    }
}
