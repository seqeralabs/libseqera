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

import io.seqera.random.LongRndKey
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import static io.seqera.data.workqueue.MessageConsumer.Decision.ACK
import static io.seqera.data.workqueue.MessageConsumer.Decision.RETRY
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class AbstractWorkQueueLocalTest extends Specification {

    @Inject
    LocalWorkQueue target

    def 'should offer and consume some messages' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"

        and:
        def stream = new TestQueue(target)
        def queue = new ArrayBlockingQueue(10)
        and:
        stream.addConsumer(id1, { it, lease -> queue.add(it); ACK })

        when:
        stream.offer(id1, new TestMessage('one','two'))
        stream.offer(id1, new TestMessage('alpha','omega'))
        then:
        queue.take()==new TestMessage('one','two')
        queue.take()==new TestMessage('alpha','omega')
        
        cleanup:
        stream.close()
    }

    def 'a retrying consumer should be paced by the poll interval, not spin hot' () {
        given: 'a local stream with ZERO retry delay, isolating the dispatcher pacing'
        def id1 = "stream-${LongRndKey.rndHex()}"
        def local = new LocalWorkQueue()
        local.@retryDelay = Duration.ZERO
        def stream = new TestQueue(local)     // pollInterval = 1s
        def invocations = new AtomicInteger()

        when: 'a single message whose consumer always asks for a retry'
        stream.addConsumer(id1, { it, lease -> invocations.incrementAndGet(); RETRY })
        stream.offer(id1, new TestMessage('a', 'b'))
        sleep 2_500

        then: 'invocations are bounded by the poll cadence - a RETRY is not progress'
        invocations.get() <= 4

        cleanup:
        stream.close()
    }

}
