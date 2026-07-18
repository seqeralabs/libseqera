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
import java.util.concurrent.atomic.AtomicInteger

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.seqera.random.LongRndKey
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

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

    def 'should register backlog gauge tied to queue length'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def queue = TestQueue.withRegistry(target, registry)
        def queueId = "queue-${LongRndKey.rndHex()}"
        def sink = new LinkedBlockingQueue()

        when:
        queue.addConsumer(queueId, { msg -> sink.add(msg); true })
        // immediately offer two entries before the consumer thread drains them
        queue.offer(queueId, new TestMessage('a','b'))
        queue.offer(queueId, new TestMessage('c','d'))

        then:
        def gauge = registry.find('seqera.workqueue.entries')
                .tag('queue', 'test-queue')
                .tag('queue_id', queueId)
                .gauge()
        gauge != null
        // gauge value tracks the underlying length() — eventually 0 after drain
        new PollingConditions(timeout: 5).eventually {
            assert gauge.value() == 0d
            assert sink.size() == 2
        }

        cleanup:
        queue.close()
    }

    def 'should increment processed counter and record timer on success'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def queue = TestQueue.withRegistry(target, registry)
        def queueId = "queue-${LongRndKey.rndHex()}"
        def seen = new AtomicInteger()

        when:
        queue.addConsumer(queueId, { msg -> seen.incrementAndGet(); true })
        queue.offer(queueId, new TestMessage('a','b'))
        queue.offer(queueId, new TestMessage('c','d'))
        queue.offer(queueId, new TestMessage('e','f'))

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
        queue.close()
    }

    def 'should count consumer-rejected message as active'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalWorkQueue()
        def queue = TestQueue.withRegistry(target, registry)
        def queueId = "queue-${LongRndKey.rndHex()}"
        def attempts = new AtomicInteger()

        when:
        // first call returns false, then true — Local impl re-queues after the poll interval
        queue.addConsumer(queueId, { msg ->
            attempts.incrementAndGet() == 1 ? false : true
        })
        queue.offer(queueId, new TestMessage('a','b'))

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
        queue.close()
    }

    def 'should register no meters when using the no-op 1-arg constructor'() {
        given:
        // 1-arg constructor → no metrics
        def target = new LocalWorkQueue()
        def queue = new TestQueue(target)
        def queueId = "queue-${LongRndKey.rndHex()}"
        def sink = new LinkedBlockingQueue()

        when:
        queue.addConsumer(queueId, { msg -> sink.add(msg); true })
        queue.offer(queueId, new TestMessage('a','b'))

        then:
        // no exceptions, message still flows
        sink.poll(5, TimeUnit.SECONDS) == new TestMessage('a','b')

        cleanup:
        queue.close()
    }
}
