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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import io.micronaut.context.ApplicationContext
import io.seqera.data.stream.impl.RedisMessageStream
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.random.LongRndKey
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Testcontainers-backed verification of the async lease model against a real Redis
 * (consumer-group PEL semantics). Covers spec §10 tests: 3 (no reclaim of live work,
 * single- and two-instance), 4 (crash failover), 7 (max-processing-time safety valve).
 *
 * The test config sets {@code claim-timeout = 1s}; the streams below heartbeat every
 * 300ms so an alive owner keeps its lease.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class AsyncStreamRedisTest extends Specification implements RedisTestContainer {

    private ApplicationContext newContext() {
        // all contexts read the same redis.host/redis.port system properties set by the
        // RedisTestContainer trait, so they share one Redis but each gets its own
        // RedisMessageStream bean (distinct consumer name) => independent instances
        return ApplicationContext.run('test', 'redis')
    }

    // §10.3 (single instance) — a handler running longer than claim-timeout is not
    // reclaimed by this instance's own poll while it is alive/heartbeated
    def 'should not reclaim live work within a single instance' () {
        given:
        def ctx = newContext()
        def target = ctx.getBean(RedisMessageStream)
        // concurrency 2 keeps the dispatcher polling while the one message is in-flight
        def stream = new TunableStream(target,
                concurrency: 2,
                pollInterval: Duration.ofMillis(200),
                heartbeatInterval: Duration.ofMillis(300))
        def id = "stream-${LongRndKey.rndHex()}"
        def calls = new AtomicInteger()
        def done = new CountDownLatch(1)

        when:
        // handler runs ~3s (>> claim-timeout 1s); the lease is heartbeated so it is never
        // reclaimed and the dispatcher's own poll never re-delivers it
        stream.addConsumer(id, { msg ->
            calls.incrementAndGet()
            Thread.sleep(3_000)
            done.countDown()
            true
        })
        stream.offer(id, 'long-running')

        then:
        done.await(8, TimeUnit.SECONDS)
        and:
        // give any spurious re-delivery a chance to show up, then assert single execution
        sleep 1_000
        calls.get() == 1

        cleanup:
        stream.close()
        ctx.stop()
    }

    // §10.3 (two instances) — a live, heartbeated owner is not reclaimed by a peer
    def 'should not reclaim live work across two instances' () {
        given:
        def ctxA = newContext()
        def ctxB = newContext()
        def targetA = ctxA.getBean(RedisMessageStream)
        def targetB = ctxB.getBean(RedisMessageStream)
        def streamA = new TunableStream(targetA,
                concurrency: 1, pollInterval: Duration.ofMillis(200), heartbeatInterval: Duration.ofMillis(300))
        def streamB = new TunableStream(targetB,
                concurrency: 1, pollInterval: Duration.ofMillis(200), heartbeatInterval: Duration.ofMillis(300))
        def id = "stream-${LongRndKey.rndHex()}"
        // shared across both instances: total number of times the message is processed
        def calls = new AtomicInteger()
        def done = new CountDownLatch(1)

        when:
        def handler = { msg ->
            calls.incrementAndGet()
            Thread.sleep(3_000)   // > claim-timeout, but heartbeated -> no reclaim by peer
            done.countDown()
            true
        }
        streamA.addConsumer(id, handler)
        streamB.addConsumer(id, handler)
        streamA.offer(id, 'once')

        then:
        done.await(8, TimeUnit.SECONDS)
        and:
        sleep 1_500  // longer than claim-timeout, let any duplicate reclaim surface
        calls.get() == 1

        cleanup:
        streamA.close()
        streamB.close()
        ctxA.stop()
        ctxB.stop()
    }

    // §10.4 — a non-heartbeating (crashed) owner's message is reclaimed by a peer after
    // claim-timeout and processed there
    def 'should fail over to a peer when the owner stops heartbeating' () {
        given:
        def ctxDead = newContext()
        def ctxLive = newContext()
        def targetDead = ctxDead.getBean(RedisMessageStream)
        def targetLive = ctxLive.getBean(RedisMessageStream)
        // 'dead' owner: picks up the message and hangs, and never heartbeats (interval 1h)
        // so its lease idle-time grows and becomes reclaimable after claim-timeout
        def streamDead = new TunableStream(targetDead,
                concurrency: 1, pollInterval: Duration.ofMillis(200), heartbeatInterval: Duration.ofHours(1))
        def streamLive = new TunableStream(targetLive,
                concurrency: 1, pollInterval: Duration.ofMillis(200), heartbeatInterval: Duration.ofMillis(300))
        def id = "stream-${LongRndKey.rndHex()}"
        def hang = new CountDownLatch(1)
        def deadStarted = new CountDownLatch(1)
        def processedByLive = new CountDownLatch(1)

        when: 'only the dead owner is consuming, so it is guaranteed to pick up the message'
        streamDead.addConsumer(id, { msg -> deadStarted.countDown(); hang.await(); true })
        streamDead.offer(id, 'orphan')

        then: 'the dead owner picks it up and then hangs without heartbeating'
        deadStarted.await(5, TimeUnit.SECONDS)

        when: 'a live peer joins the group'
        streamLive.addConsumer(id, { msg -> processedByLive.countDown(); true })

        then: 'it reclaims the orphaned entry after the claim timeout and processes it'
        processedByLive.await(8, TimeUnit.SECONDS)

        cleanup:
        hang.countDown()
        streamDead.close()
        streamLive.close()
        ctxDead.stop()
        ctxLive.stop()
    }

    // §10.7 — a single invocation exceeding max-processing-time has its lease released
    // (stops being renewed), so the message is reclaimed and re-delivered
    def 'should release the lease of an invocation exceeding max-processing-time' () {
        given:
        def ctx = newContext()
        def target = ctx.getBean(RedisMessageStream)
        def stream = new TunableStream(target,
                concurrency: 2,
                pollInterval: Duration.ofMillis(200),
                heartbeatInterval: Duration.ofMillis(300),
                maxProcessingTime: Duration.ofSeconds(1))
        def id = "stream-${LongRndKey.rndHex()}"
        def calls = new AtomicInteger()
        def hang = new CountDownLatch(1)
        def redelivered = new CountDownLatch(1)

        when:
        stream.addConsumer(id, { msg ->
            def n = calls.incrementAndGet()
            if (n == 1) {
                // first invocation hangs past max-processing-time (1s) -> lease released
                hang.await()
                return true
            }
            // the re-delivered invocation completes normally
            redelivered.countDown()
            return true
        })
        stream.offer(id, 'hung')

        then: 'the hung invocation is evicted and the message is re-delivered'
        redelivered.await(10, TimeUnit.SECONDS)
        calls.get() >= 2

        cleanup:
        hang.countDown()
        stream.close()
        ctx.stop()
    }

}
