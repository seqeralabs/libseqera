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

package io.seqera.lock.local

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import io.seqera.lock.LockConfig
import spock.lang.Specification

class LocalLockManagerTest extends Specification {

    LockConfig createConfig() {
        def config = new LockConfig('test')
        config.setAcquireRetryInterval(Duration.ofMillis(50))
        return config
    }

    def 'should acquire and release lock'() {
        given:
        def manager = new LocalLockManager(createConfig())

        when:
        def lock = manager.tryAcquire('test-lock')

        then:
        lock != null

        when:
        def released = lock.release()

        then:
        released
    }

    def 'should fail to acquire lock held by another'() {
        given:
        def manager = new LocalLockManager(createConfig())

        when:
        def lock1 = manager.tryAcquire('test-lock')
        def lock2 = manager.tryAcquire('test-lock')

        then:
        lock1 != null
        lock2 == null

        cleanup:
        lock1?.release()
    }

    def 'should acquire lock after release'() {
        given:
        def manager = new LocalLockManager(createConfig())

        when:
        def lock1 = manager.tryAcquire('test-lock')
        lock1.release()
        def lock2 = manager.tryAcquire('test-lock')

        then:
        lock2 != null

        cleanup:
        lock2?.release()
    }

    def 'should support multiple independent locks'() {
        given:
        def manager = new LocalLockManager(createConfig())

        when:
        def lock1 = manager.tryAcquire('lock-1')
        def lock2 = manager.tryAcquire('lock-2')

        then:
        lock1 != null
        lock2 != null

        cleanup:
        lock1?.release()
        lock2?.release()
    }

    def 'should block and acquire lock with timeout'() {
        given:
        def manager = new LocalLockManager(createConfig())

        when:
        def lock = manager.acquire('test-lock', Duration.ofSeconds(5))

        then:
        lock != null

        cleanup:
        lock?.release()
    }

    def 'should timeout when lock not available'() {
        given:
        def manager = new LocalLockManager(createConfig())
        def lock1 = manager.tryAcquire('test-lock')

        when:
        manager.acquire('test-lock', Duration.ofMillis(200))

        then:
        thrown(TimeoutException)

        cleanup:
        lock1?.release()
    }

    def 'should wait and acquire lock when released'() {
        given:
        def manager = new LocalLockManager(createConfig())
        def lock1 = manager.tryAcquire('test-lock')
        def acquiredLatch = new CountDownLatch(1)
        def lock2Holder = new Object[1]

        when:
        Thread.start {
            lock2Holder[0] = manager.acquire('test-lock', Duration.ofSeconds(5))
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
        def manager = new LocalLockManager(createConfig())

        when:
        manager.tryAcquire('test-lock').withCloseable { acquired ->
            // Lock should be held here
            assert manager.tryAcquire('test-lock') == null
        }
        // Lock should be released after block
        def newLock = manager.tryAcquire('test-lock')

        then:
        newLock != null

        cleanup:
        newLock?.release()
    }

    def 'should handle concurrent lock attempts'() {
        given:
        def manager = new LocalLockManager(createConfig())
        def successCount = new AtomicInteger(0)
        def threadCount = 10
        def latch = new CountDownLatch(threadCount)

        when:
        (1..threadCount).each {
            Thread.start {
                def lock = manager.tryAcquire('test-lock')
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

    def 'should not release lock held by another instance'() {
        given:
        def manager = new LocalLockManager(createConfig())
        def lock = manager.tryAcquire('test-lock')

        when:
        // Simulate another instance trying to release
        def released = manager.release('test-lock', 'wrong-instance-id')

        then:
        !released
        // Original lock should still be held
        manager.tryAcquire('test-lock') == null

        cleanup:
        lock?.release()
    }
}
