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

package io.seqera.data.workqueue

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import io.seqera.random.LongRndKey
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Async-processing behaviour of {@link AbstractWorkQueue} exercised over the
 * in-memory {@link LocalWorkQueue} backend, so these run WITHOUT Docker.
 *
 * Covers: non-blocking dispatch, concurrency, re-poll cadence, serial-per-command,
 * backpressure, and concurrency==1 default.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class AsyncWorkQueueLocalTest extends Specification {

    // a slow handler on queue A must not delay a fast handler on queue B
    def 'should not block a fast queue behind a slow one' () {
        given:
        def target = new LocalWorkQueue()
        def queue = new TunableQueue(target, concurrency: 2, pollInterval: Duration.ofMillis(100))
        def idA = "queue-${LongRndKey.rndHex()}"
        def idB = "queue-${LongRndKey.rndHex()}"
        def slowDone = new CountDownLatch(1)
        def fastDone = new CountDownLatch(1)

        when:
        queue.addConsumer(idA, { msg -> Thread.sleep(3_000); slowDone.countDown(); true })
        queue.addConsumer(idB, { msg -> fastDone.countDown(); true })
        and:
        queue.offer(idA, 'slow')
        queue.offer(idB, 'fast')

        then:
        // the fast handler completes well before the slow one finishes
        fastDone.await(2, TimeUnit.SECONDS)
        slowDone.count == 1

        cleanup:
        queue.close()
    }

    // N messages with a slow handler complete in ~max(handler), not ~sum
    def 'should process messages concurrently' () {
        given:
        def target = new LocalWorkQueue()
        def queue = new TunableQueue(target, concurrency: 4, pollInterval: Duration.ofMillis(100))
        def id = "queue-${LongRndKey.rndHex()}"
        def done = new CountDownLatch(4)

        when:
        queue.addConsumer(id, { msg -> Thread.sleep(500); done.countDown(); true })
        def t0 = System.currentTimeMillis()
        4.times { queue.offer(id, "msg-$it".toString()) }

        then:
        done.await(5, TimeUnit.SECONDS)
        def elapsed = System.currentTimeMillis() - t0
        // 4 x 500ms serial would be ~2000ms; concurrent should be well under that
        elapsed < 1_500

        cleanup:
        queue.close()
    }

    // a not-yet-terminal command is re-invoked at ~pollInterval (Model B)
    def 'should re-poll a not-yet-terminal command at poll interval' () {
        given:
        def poll = Duration.ofMillis(300)
        def target = new LocalWorkQueue()
        def queue = new TunableQueue(target, concurrency: 1, pollInterval: poll)
        def id = "queue-${LongRndKey.rndHex()}"
        def timestamps = new ConcurrentLinkedQueue<Long>()

        when:
        // record the wall-clock of each invocation; stay non-terminal for 5 calls, then ack
        queue.addConsumer(id, { msg ->
            timestamps.add(System.currentTimeMillis())
            return timestamps.size() >= 5
        })
        queue.offer(id, 'running')

        then:
        new PollingConditions(timeout: 10).eventually {
            assert timestamps.size() == 5
        }
        and:
        def times = timestamps.toList()
        def gaps = (1..<times.size()).collect { times[it] - times[it-1] }
        // each re-poll gap is ~pollInterval (allow generous slack for scheduling)
        gaps.every { it >= 150 && it <= 1_500 }

        cleanup:
        queue.close()
    }

    // never two concurrent accept() invocations for the same command
    def 'should invoke a command strictly serially across re-polls' () {
        given:
        def target = new LocalWorkQueue()
        def queue = new TunableQueue(target, concurrency: 4, pollInterval: Duration.ofMillis(150))
        def id = "queue-${LongRndKey.rndHex()}"
        def inProgress = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def calls = new AtomicInteger()

        when:
        queue.addConsumer(id, { msg ->
            def now = inProgress.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, Math::max)
            Thread.sleep(100)
            inProgress.decrementAndGet()
            return calls.incrementAndGet() >= 4
        })
        queue.offer(id, 'running')

        then:
        new PollingConditions(timeout: 10).eventually {
            assert calls.get() >= 4
        }
        and:
        // one message => the same lease is never processed by two workers at once
        maxConcurrent.get() == 1

        cleanup:
        queue.close()
    }

    // with pool size K and more than K ready messages, at most K run at once
    def 'should bound concurrent handlers by the pool size (backpressure)' () {
        given:
        def target = new LocalWorkQueue()
        def queue = new TunableQueue(target, concurrency: 2, pollInterval: Duration.ofMillis(100))
        def id = "queue-${LongRndKey.rndHex()}"
        def inProgress = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def done = new CountDownLatch(6)

        when:
        queue.addConsumer(id, { msg ->
            def now = inProgress.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, Math::max)
            Thread.sleep(300)
            inProgress.decrementAndGet()
            done.countDown()
            true
        })
        6.times { queue.offer(id, "msg-$it".toString()) }

        then:
        done.await(10, TimeUnit.SECONDS)
        maxConcurrent.get() <= 2

        cleanup:
        queue.close()
    }

    // default concurrency is 1: at most one handler runs at a time
    def 'should run at most one handler with the default concurrency' () {
        given:
        def target = new LocalWorkQueue()
        // default TunableQueue -> concurrency 1
        def queue = new TunableQueue(target, pollInterval: Duration.ofMillis(100))
        def id = "queue-${LongRndKey.rndHex()}"
        def inProgress = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def done = new CountDownLatch(4)

        when:
        queue.addConsumer(id, { msg ->
            def now = inProgress.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, Math::max)
            Thread.sleep(150)
            inProgress.decrementAndGet()
            done.countDown()
            true
        })
        4.times { queue.offer(id, "msg-$it".toString()) }

        then:
        done.await(10, TimeUnit.SECONDS)
        maxConcurrent.get() == 1

        cleanup:
        queue.close()
    }

}
