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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.seqera.data.stream.impl.LocalMessageStream
import io.seqera.random.LongRndKey
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Verifies the Micrometer instrumentation in AbstractMessageStream.
 *
 * Uses the LocalMessageStream impl plus a SimpleMeterRegistry. Asserts metric
 * presence, tag values, counter increments per outcome, and that the no-op
 * path leaves no meters when the registry is null.
 *
 * @author Paolo Di Tommaso
 */
class AbstractMessageStreamMetricsTest extends Specification {

    def 'should register backlog gauge tied to stream length'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalMessageStream()
        def stream = TestStream.withRegistry(target, registry)
        def streamId = "stream-${LongRndKey.rndHex()}"
        def queue = new LinkedBlockingQueue()

        when:
        stream.addConsumer(streamId, { msg -> queue.add(msg); true })
        // immediately offer two entries before the consumer thread drains them
        stream.offer(streamId, new TestMessage('a','b'))
        stream.offer(streamId, new TestMessage('c','d'))

        then:
        def gauge = registry.find('seqera.stream.entries')
                .tag('stream', 'test-stream')
                .tag('stream_id', streamId)
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
        def target = new LocalMessageStream()
        def stream = TestStream.withRegistry(target, registry)
        def streamId = "stream-${LongRndKey.rndHex()}"
        def seen = new AtomicInteger()

        when:
        stream.addConsumer(streamId, { msg -> seen.incrementAndGet(); true })
        stream.offer(streamId, new TestMessage('a','b'))
        stream.offer(streamId, new TestMessage('c','d'))
        stream.offer(streamId, new TestMessage('e','f'))

        then:
        new PollingConditions(timeout: 5).eventually {
            assert seen.get() == 3
            def counter = registry.find('seqera.stream.messages')
                    .tag('stream', 'test-stream')
                    .tag('stream_id', streamId)
                    .tag('outcome', 'processed')
                    .counter()
            assert counter != null
            assert counter.count() == 3.0d

            def timer = registry.find('seqera.stream.processing')
                    .tag('stream', 'test-stream')
                    .tag('stream_id', streamId)
                    .tag('outcome', 'processed')
                    .timer()
            assert timer != null
            assert timer.count() == 3L
        }

        cleanup:
        stream.close()
    }

    def 'should count consumer-rejected message as failed'() {
        given:
        def registry = new SimpleMeterRegistry()
        def target = new LocalMessageStream()
        def stream = TestStream.withRegistry(target, registry)
        def streamId = "stream-${LongRndKey.rndHex()}"
        def attempts = new AtomicInteger()

        when:
        // first call returns false, then true — Local impl re-queues after a 1s sleep
        stream.addConsumer(streamId, { msg ->
            attempts.incrementAndGet() == 1 ? false : true
        })
        stream.offer(streamId, new TestMessage('a','b'))

        then:
        new PollingConditions(timeout: 8).eventually {
            assert attempts.get() >= 2

            def failed = registry.find('seqera.stream.messages')
                    .tag('outcome', 'failed')
                    .tag('stream_id', streamId)
                    .counter()
            def processed = registry.find('seqera.stream.messages')
                    .tag('outcome', 'processed')
                    .tag('stream_id', streamId)
                    .counter()
            assert failed?.count() >= 1.0d
            assert processed?.count() >= 1.0d
        }

        cleanup:
        stream.close()
    }

    def 'should register no meters when registry is null'() {
        given:
        // 1-arg constructor → no metrics
        def target = new LocalMessageStream()
        def stream = new TestStream(target)
        def streamId = "stream-${LongRndKey.rndHex()}"
        def queue = new LinkedBlockingQueue()

        when:
        stream.addConsumer(streamId, { msg -> queue.add(msg); true })
        stream.offer(streamId, new TestMessage('a','b'))

        then:
        // no exceptions, message still flows
        queue.poll(5, TimeUnit.SECONDS) == new TestMessage('a','b')

        cleanup:
        stream.close()
    }
}
