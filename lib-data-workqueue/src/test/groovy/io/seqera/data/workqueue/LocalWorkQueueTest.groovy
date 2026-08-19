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

import io.seqera.random.LongRndKey
import spock.lang.Specification

import io.micronaut.context.annotation.Property
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import static io.seqera.data.workqueue.MessageConsumer.Decision.ACK
import static io.seqera.data.workqueue.MessageConsumer.Decision.DEFERRED
import static io.seqera.data.workqueue.MessageConsumer.Decision.RETRY

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
@Property(name = 'workqueue.local.retry-delay', value = '250ms')
class LocalWorkQueueTest extends Specification {

    @Inject
    LocalWorkQueue contextQueue

    def 'the retry delay should bind from configuration' () {
        expect: 'the @Value binding resolved the property - a key typo would silently fall back to 1s'
        contextQueue.@retryDelay == Duration.ofMillis(250)
    }

    def 'should offer and consume a value' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def id2 = "stream-${LongRndKey.rndHex()}"
        and:
        def stream = new LocalWorkQueue()
        and:
        stream.init(id1)
        stream.init(id2)
        when:
        stream.offer(id1, 'one')
        and:
        stream.offer(id2, 'alpha')
        stream.offer(id2, 'delta')
        stream.offer(id2, 'gamma')

        then:
        stream.consume(id1, { it, lease -> assert it=='one'; ACK }) == ACK
        and:
        stream.consume(id2, { it, lease -> assert it=='alpha'; ACK }) == ACK
        stream.consume(id2, { it, lease -> assert it=='delta'; ACK }) == ACK
        stream.consume(id2, { it, lease -> assert it=='gamma'; ACK }) == ACK
        and:
        stream.consume(id2, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
    }

    def 'should offer and consume a value with a failure' () {
        given: 'a zero retry delay: this test covers settlement ordering, not pacing'
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.@retryDelay = Duration.ZERO
        stream.init(id1)
        when:
        stream.offer(id1, 'alpha')
        stream.offer(id1, 'delta')
        stream.offer(id1, 'gamma')

        then:
        stream.consume(id1, { it, lease -> assert it=='alpha'; ACK }) == ACK
        and:
        // a consumer throw settles as RETRY - the message is re-queued at the tail
        stream.consume(id1, { it, lease -> throw new RuntimeException("Oops") }) == RETRY
        and:
        // next message is 'gamma' as expected
        stream.consume(id1, { it, lease -> assert it=='gamma'; ACK }) == ACK
        and:
        // now the errored message is available again
        stream.consume(id1, { it, lease -> assert it=='delta'; ACK }) == ACK
        and:
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null

        when:
        stream.offer(id1, 'something')
        then:
        stream.consume(id1, { it, lease -> assert it=='something'; ACK }) == ACK
    }

    def 'should validate length method' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.init(id1)

        expect:
        stream.length(id1) == 0

        when:
        stream.offer(id1, 'alpha')
        stream.offer(id1, 'delta')
        stream.offer(id1, 'gamma')
        then:
        stream.length(id1) == 3

        when:
        stream.consume(id1, { it, lease -> ACK })
        then:
        stream.length(id1) == 2
    }

    def 'deferred message should stay unavailable until the lease settles' () {
        given: 'a zero retry delay: this test covers settlement semantics, not pacing'
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.@retryDelay = Duration.ZERO
        stream.init(id1)
        MessageLease held = null

        when:
        stream.offer(id1, 'alpha')
        then:
        stream.consume(id1, { it, lease -> held = lease; DEFERRED }) == DEFERRED
        and: 'the message is neither queued nor redeliverable while the lease is open'
        stream.length(id1) == 0
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null

        when: 'retry makes it redeliverable (zero delay here)'
        held.retry()
        then:
        stream.length(id1) == 1
        stream.consume(id1, { it, lease -> assert it=='alpha'; ACK }) == ACK
    }

    def 'ack should remove a deferred message' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.init(id1)
        MessageLease held = null

        when:
        stream.offer(id1, 'alpha')
        stream.consume(id1, { it, lease -> held = lease; DEFERRED })
        and: 'settle from a different thread'
        def settler = new Thread({ held.ack() })
        settler.start()
        settler.join()
        then:
        stream.length(id1) == 0
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
    }

    def 'double settlement should be a no-op' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.init(id1)
        MessageLease held = null

        when:
        stream.offer(id1, 'alpha')
        stream.consume(id1, { it, lease -> held = lease; DEFERRED })
        and: 'first call wins - the late retry cannot resurrect the acked message'
        held.ack()
        held.retry()
        held.retry()
        then:
        stream.length(id1) == 0
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
    }

    def 'retry should delay redelivery by the local retry delay' () {
        given: 'a stream with a 300ms retry delay - the local analog of the Redis claim cadence'
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.@retryDelay = Duration.ofMillis(300)
        stream.init(id1)
        MessageLease held = null

        when:
        stream.offer(id1, 'alpha')
        stream.consume(id1, { it, lease -> held = lease; DEFERRED })
        held.retry()
        then: 'the message is NOT redeliverable before the delay - no hot retry loop'
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
        and: 'it becomes redeliverable once the delay elapses'
        sleep 400
        stream.consume(id1, { it, lease -> assert it=='alpha'; ACK }) == ACK
    }

    def 'retryAfter should delay redelivery by the requested delay' () {
        given: 'a stream whose plain retry delay is tiny, so the explicit delay is what gates'
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.@retryDelay = Duration.ofMillis(10)
        stream.init(id1)
        MessageLease held = null

        when:
        stream.offer(id1, 'alpha')
        stream.consume(id1, { it, lease -> held = lease; DEFERRED })
        held.retryAfter(Duration.ofMillis(400))
        then: 'not redeliverable before the requested delay'
        sleep 100
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
        and: 'redeliverable once it elapses'
        sleep 400
        stream.consume(id1, { it, lease -> assert it=='alpha'; ACK }) == ACK
        and: 'a late double-settlement is a no-op'
        held.retry()
        stream.length(id1) == 0
    }

    def 'consumer throw should settle as retry leaving nothing leased' () {
        given: 'a zero retry delay: this test covers settlement semantics, not pacing'
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalWorkQueue()
        stream.@retryDelay = Duration.ZERO
        stream.init(id1)
        MessageLease held = null

        when:
        stream.offer(id1, 'alpha')
        then:
        stream.consume(id1, { it, lease -> held = lease; throw new RuntimeException('Oops') }) == RETRY
        and: 'the message is redeliverable (zero delay here)'
        stream.length(id1) == 1
        and: 'the lease is already settled - a late retry cannot duplicate the message'
        held.retry()
        stream.length(id1) == 1
        stream.consume(id1, { it, lease -> assert it=='alpha'; ACK }) == ACK
    }

}
