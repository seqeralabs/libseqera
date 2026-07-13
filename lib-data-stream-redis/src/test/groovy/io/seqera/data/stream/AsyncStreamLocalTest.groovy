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

package io.seqera.data.stream

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import io.seqera.data.stream.impl.LocalMessageStream
import io.seqera.random.LongRndKey
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Async-processing behaviour of {@link AbstractMessageStream} exercised over the
 * in-memory {@link LocalMessageStream} backend, so these run WITHOUT Docker.
 *
 * Covers spec §10 tests: 1 (non-blocking), 2 (concurrency), 5 (re-poll cadence),
 * 6 (serial per command), 8 (backpressure), 9 (concurrency==1 default).
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class AsyncStreamLocalTest extends Specification {

    // §10.1 — a slow handler on stream A must not delay a fast handler on stream B
    def 'should not block a fast stream behind a slow one' () {
        given:
        def target = new LocalMessageStream()
        def stream = new TunableStream(target, concurrency: 2, pollInterval: Duration.ofMillis(100))
        def idA = "stream-${LongRndKey.rndHex()}"
        def idB = "stream-${LongRndKey.rndHex()}"
        def slowDone = new CountDownLatch(1)
        def fastDone = new CountDownLatch(1)

        when:
        stream.addConsumer(idA, { msg -> Thread.sleep(3_000); slowDone.countDown(); true })
        stream.addConsumer(idB, { msg -> fastDone.countDown(); true })
        and:
        stream.offer(idA, 'slow')
        stream.offer(idB, 'fast')

        then:
        // the fast handler completes well before the slow one finishes
        fastDone.await(2, TimeUnit.SECONDS)
        slowDone.count == 1

        cleanup:
        stream.close()
    }

    // §10.2 — N messages with a slow handler complete in ~max(handler), not ~sum
    def 'should process messages concurrently' () {
        given:
        def target = new LocalMessageStream()
        def stream = new TunableStream(target, concurrency: 4, pollInterval: Duration.ofMillis(100))
        def id = "stream-${LongRndKey.rndHex()}"
        def done = new CountDownLatch(4)

        when:
        stream.addConsumer(id, { msg -> Thread.sleep(500); done.countDown(); true })
        def t0 = System.currentTimeMillis()
        4.times { stream.offer(id, "msg-$it".toString()) }

        then:
        done.await(5, TimeUnit.SECONDS)
        def elapsed = System.currentTimeMillis() - t0
        // 4 x 500ms serial would be ~2000ms; concurrent should be well under that
        elapsed < 1_500

        cleanup:
        stream.close()
    }

    // §10.5 — a not-yet-terminal command is re-invoked at ~pollInterval (Model B)
    def 'should re-poll a not-yet-terminal command at poll interval' () {
        given:
        def poll = Duration.ofMillis(300)
        def target = new LocalMessageStream()
        def stream = new TunableStream(target, concurrency: 1, pollInterval: poll)
        def id = "stream-${LongRndKey.rndHex()}"
        def timestamps = new ConcurrentLinkedQueue<Long>()

        when:
        // record the wall-clock of each invocation; stay non-terminal for 5 calls, then ack
        stream.addConsumer(id, { msg ->
            timestamps.add(System.currentTimeMillis())
            return timestamps.size() >= 5
        })
        stream.offer(id, 'running')

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
        stream.close()
    }

    // §10.6 — never two concurrent accept() invocations for the same command
    def 'should invoke a command strictly serially across re-polls' () {
        given:
        def target = new LocalMessageStream()
        def stream = new TunableStream(target, concurrency: 4, pollInterval: Duration.ofMillis(150))
        def id = "stream-${LongRndKey.rndHex()}"
        def inProgress = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def calls = new AtomicInteger()

        when:
        stream.addConsumer(id, { msg ->
            def now = inProgress.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, Math::max)
            Thread.sleep(100)
            inProgress.decrementAndGet()
            return calls.incrementAndGet() >= 4
        })
        stream.offer(id, 'running')

        then:
        new PollingConditions(timeout: 10).eventually {
            assert calls.get() >= 4
        }
        and:
        // one message => the same lease is never processed by two workers at once
        maxConcurrent.get() == 1

        cleanup:
        stream.close()
    }

    // §10.8 — with pool size K and more than K ready messages, at most K run at once
    def 'should bound concurrent handlers by the pool size (backpressure)' () {
        given:
        def target = new LocalMessageStream()
        def stream = new TunableStream(target, concurrency: 2, pollInterval: Duration.ofMillis(100))
        def id = "stream-${LongRndKey.rndHex()}"
        def inProgress = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def done = new CountDownLatch(6)

        when:
        stream.addConsumer(id, { msg ->
            def now = inProgress.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, Math::max)
            Thread.sleep(300)
            inProgress.decrementAndGet()
            done.countDown()
            true
        })
        6.times { stream.offer(id, "msg-$it".toString()) }

        then:
        done.await(10, TimeUnit.SECONDS)
        maxConcurrent.get() <= 2

        cleanup:
        stream.close()
    }

    // §10.9 — default concurrency is 1: at most one handler runs at a time
    def 'should run at most one handler with the default concurrency' () {
        given:
        def target = new LocalMessageStream()
        // default TunableStream -> concurrency 1
        def stream = new TunableStream(target, pollInterval: Duration.ofMillis(100))
        def id = "stream-${LongRndKey.rndHex()}"
        def inProgress = new AtomicInteger()
        def maxConcurrent = new AtomicInteger()
        def done = new CountDownLatch(4)

        when:
        stream.addConsumer(id, { msg ->
            def now = inProgress.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, Math::max)
            Thread.sleep(150)
            inProgress.decrementAndGet()
            done.countDown()
            true
        })
        4.times { stream.offer(id, "msg-$it".toString()) }

        then:
        done.await(10, TimeUnit.SECONDS)
        maxConcurrent.get() == 1

        cleanup:
        stream.close()
    }

    // self-reclaim: if the heartbeat falls behind, this instance's own poll() (XAUTOCLAIM) can
    // re-deliver an entry it is still processing. That duplicate must NOT start a second handler
    // or leak a permit (regression for the concurrency()>1 permit-leak / double-run).
    def 'self-reclaim of an in-flight entry does not double-run the handler'() {
        given: 'a backing stream that re-delivers the SAME lease id twice, then nothing'
        def deliveries = new AtomicInteger(0)
        def acks = new AtomicInteger(0)
        def target = [
                init   : { String q -> },
                offer  : { String q, String m -> },
                poll   : { String q -> deliveries.getAndIncrement() < 2 ? new MessageStream.Lease<String>('dup-id', 'payload') : null },
                renew  : { String q, String id -> },
                ack    : { String q, String id -> acks.incrementAndGet() },
                release: { String q, String id -> },
                length : { String q -> 0 }
        ] as MessageStream<String>
        def stream = new TunableStream(target, concurrency: 2, pollInterval: Duration.ofMillis(50))
        def runs = new AtomicInteger(0)
        def gate = new CountDownLatch(1)

        when: 'the handler blocks, so the entry stays in flight across the duplicate delivery'
        stream.addConsumer('q1', { msg -> runs.incrementAndGet(); gate.await(5, TimeUnit.SECONDS); true } as MessageConsumer)
        and: 'wait until the duplicate delivery has been attempted, then let a stray 2nd run surface'
        new PollingConditions(timeout: 3).eventually { deliveries.get() >= 2 }
        sleep(300)

        then: 'the handler ran exactly once despite the duplicate delivery'
        runs.get() == 1

        when: 'the handler completes'
        gate.countDown()

        then: 'the entry is acked exactly once (no permit leak / double-run)'
        new PollingConditions(timeout: 5).eventually { acks.get() == 1 }

        cleanup:
        gate.countDown()
        stream.close()
    }

}
