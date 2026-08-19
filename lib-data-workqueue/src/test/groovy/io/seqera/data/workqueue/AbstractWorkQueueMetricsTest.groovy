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

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.seqera.random.LongRndKey
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import static io.seqera.data.workqueue.MessageConsumer.Decision.ACK
import static io.seqera.data.workqueue.MessageConsumer.Decision.DEFERRED
import static io.seqera.data.workqueue.MessageConsumer.Decision.RETRY

/**
 * Verifies the Micrometer instrumentation in AbstractWorkQueue.
 *
 * Uses the LocalWorkQueue impl plus a SimpleMeterRegistry. Asserts metric
 * presence, tag values, counter increments per outcome, and that the no-op
 * path leaves no meters when the registry is null.
 *
 * @author Paolo Di Tommaso
 */
class AbstractWorkQueueMetricsTest extends Specification {

    def 'should register backlog gauge tied to stream length'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def stream = TestQueue.withRegistry(target, registry)
        def queueId = "stream-${LongRndKey.rndHex()}"
        def queue = new LinkedBlockingQueue()

        when:
        stream.addConsumer(queueId, { msg, lease -> queue.add(msg); ACK })
        // immediately offer two entries before the consumer thread drains them
        stream.offer(queueId, new TestMessage('a','b'))
        stream.offer(queueId, new TestMessage('c','d'))

        then:
        def gauge = registry.find('seqera.workqueue.entries')
                .tag('queue', 'test-queue')
                .tag('queue_id', queueId)
                .gauge()
        gauge != null
        // gauge value tracks the underlying length() — eventually 0 after drain
        new PollingConditions(timeout: 5).eventually {
            assert gauge.value() == 0d
            assert queue.size() == 2
        }

        cleanup:
        stream.close()
    }

    def 'should increment processed counter and record timer on success'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def stream = TestQueue.withRegistry(target, registry)
        def queueId = "stream-${LongRndKey.rndHex()}"
        def seen = new AtomicInteger()

        when:
        stream.addConsumer(queueId, { msg, lease -> seen.incrementAndGet(); ACK })
        stream.offer(queueId, new TestMessage('a','b'))
        stream.offer(queueId, new TestMessage('c','d'))
        stream.offer(queueId, new TestMessage('e','f'))

        then:
        new PollingConditions(timeout: 5).eventually {
            assert seen.get() == 3
            def counter = registry.find('seqera.workqueue.messages')
                    .tag('queue', 'test-queue')
                    .tag('queue_id', queueId)
                    .tag('outcome', 'processed')
                    .counter()
            assert counter != null
            assert counter.count() == 3.0d

            def timer = registry.find('seqera.workqueue.processing')
                    .tag('queue', 'test-queue')
                    .tag('queue_id', queueId)
                    .tag('outcome', 'processed')
                    .timer()
            assert timer != null
            assert timer.count() == 3L
        }

        cleanup:
        stream.close()
    }

    def 'should count consumer-rejected message as active'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def stream = TestQueue.withRegistry(target, registry)
        def queueId = "stream-${LongRndKey.rndHex()}"
        def attempts = new AtomicInteger()

        when:
        // first call retries, then acks — Local impl re-queues the message immediately
        stream.addConsumer(queueId, { msg, lease ->
            attempts.incrementAndGet() == 1 ? RETRY : ACK
        })
        stream.offer(queueId, new TestMessage('a','b'))

        then:
        new PollingConditions(timeout: 8).eventually {
            assert attempts.get() >= 2

            def active = registry.find('seqera.workqueue.messages')
                    .tag('outcome', 'active')
                    .tag('queue_id', queueId)
                    .counter()
            def processed = registry.find('seqera.workqueue.messages')
                    .tag('outcome', 'processed')
                    .tag('queue_id', queueId)
                    .counter()
            assert active?.count() >= 1.0d
            assert processed?.count() >= 1.0d
        }

        cleanup:
        stream.close()
    }

    def 'should register no meters when using the no-op 1-arg constructor'() {
        given:
        // 1-arg constructor → no metrics
        def target = new LocalWorkQueue()
        def stream = new TestQueue(target)
        def queueId = "stream-${LongRndKey.rndHex()}"
        def queue = new LinkedBlockingQueue()

        when:
        stream.addConsumer(queueId, { msg, lease -> queue.add(msg); ACK })
        stream.offer(queueId, new TestMessage('a','b'))

        then:
        // no exceptions, message still flows
        queue.poll(5, TimeUnit.SECONDS) == new TestMessage('a','b')

        cleanup:
        stream.close()
    }

    def 'should skip a not-ready consumer and count the poll as saturated'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def stream = TestQueue.withRegistry(target, registry)
        def queueId = "stream-${LongRndKey.rndHex()}"
        def ready = new AtomicBoolean(false)
        def queue = new LinkedBlockingQueue()
        def consumer = new MessageConsumer<TestMessage>() {
            @Override
            MessageConsumer.Decision accept(TestMessage msg, MessageLease lease) {
                queue.add(msg)
                return ACK
            }
            @Override
            boolean ready() {
                return ready.get()
            }
        }

        when:
        stream.addConsumer(queueId, consumer)
        stream.offer(queueId, new TestMessage('a','b'))

        then: 'the message is not claimed while the consumer is saturated'
        queue.poll(1, TimeUnit.SECONDS) == null
        target.length(queueId) == 1
        and: 'the skipped polls are counted as saturated, not empty'
        new PollingConditions(timeout: 5).eventually {
            def saturated = registry.find('seqera.workqueue.saturated')
                    .tag('queue', 'test-queue')
                    .tag('queue_id', queueId)
                    .counter()
            assert saturated != null
            assert saturated.count() >= 1.0d
        }

        when: 'the admission gate opens'
        ready.set(true)

        then:
        queue.poll(5, TimeUnit.SECONDS) == new TestMessage('a','b')

        cleanup:
        stream.close()
    }

    def 'should count a deferred delivery as active plus a distinct deferred counter'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def stream = TestQueue.withRegistry(target, registry)
        def queueId = "stream-${LongRndKey.rndHex()}"
        def held = new AtomicReference<MessageLease>()

        when:
        stream.addConsumer(queueId, { msg, lease -> held.compareAndSet(null, lease); DEFERRED })
        stream.offer(queueId, new TestMessage('a','b'))

        then:
        new PollingConditions(timeout: 5).eventually {
            assert held.get() != null

            def deferred = registry.find('seqera.workqueue.deferred')
                    .tag('queue', 'test-queue')
                    .tag('queue_id', queueId)
                    .counter()
            def active = registry.find('seqera.workqueue.messages')
                    .tag('outcome', 'active')
                    .tag('queue_id', queueId)
                    .counter()
            assert deferred?.count() >= 1.0d
            assert active?.count() >= 1.0d
        }

        cleanup:
        held.get()?.ack()
        stream.close()
    }
}
