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

import io.micronaut.test.extensions.spock.annotation.MicronautTest

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class LocalWorkQueueTest extends Specification {

    def 'should offer and consume a value' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def id2 = "queue-${LongRndKey.rndHex()}"
        and:
        def queue = new LocalWorkQueue()
        and:
        queue.init(id1)
        queue.init(id2)
        when:
        queue.offer(id1, 'one')
        and:
        queue.offer(id2, 'alpha')
        queue.offer(id2, 'delta')
        queue.offer(id2, 'gamma')

        then:
        queue.consume(id1, { it-> it=='one'})
        and:
        queue.consume(id2, { it-> it=='alpha'})
        queue.consume(id2, { it-> it=='delta'})
        queue.consume(id2, { it-> it=='gamma'})
        and:
        !queue.consume(id2, { it-> assert false /* <-- this should not be invoked */ })
    }

    def 'should offer and consume a value with a failure' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = new LocalWorkQueue()
        queue.init(id1)
        when:
        queue.offer(id1, 'alpha')
        queue.offer(id1, 'delta')
        queue.offer(id1, 'gamma')

        then:
        queue.consume(id1, { it-> it=='alpha'})
        and:
        // the default consume() does not catch handler exceptions: it propagates and,
        // since receive() already removed 'delta' and release() is not reached, it is dropped
        try {
            queue.consume(id1, { it-> throw new RuntimeException("Oops")})
            assert false
        }
        catch (RuntimeException e) {
            assert e.message == 'Oops'
        }
        and:
        // next message is 'gamma' as expected ('delta' was dropped on the throw)
        queue.consume(id1, { it-> it=='gamma'})
        and:
        !queue.consume(id1, { it-> assert false /* <-- this should not be invoked */ })

        when:
        queue.offer(id1, 'something')
        then:
        queue.consume(id1, { it-> it=='something'})
    }

    def 'should validate length method' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = new LocalWorkQueue()
        queue.init(id1)

        expect:
        queue.length(id1) == 0

        when:
        queue.offer(id1, 'alpha')
        queue.offer(id1, 'delta')
        queue.offer(id1, 'gamma')
        then:
        queue.length(id1) == 3

        when:
        queue.consume(id1, { it-> true})
        then:
        queue.length(id1) == 2
    }

    // Local backend: receive returns a lease; renewLease is a no-op; release re-offers
    def 'should receive and release re-offering the message' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = new LocalWorkQueue()
        queue.init(id1)
        queue.offer(id1, 'alpha')

        when: 'receive takes the message off the queue (lease id == message value)'
        def lease = queue.receive(id1)
        then:
        lease != null
        lease.message() == 'alpha'
        queue.length(id1) == 0

        when: 'renewLease is a no-op and does not throw nor alter the queue'
        queue.renewLease(id1, lease.id())
        then:
        queue.length(id1) == 0

        when: 'release re-offers the message for later redelivery'
        queue.release(id1, lease.id())
        then:
        queue.length(id1) == 1
        queue.receive(id1).message() == 'alpha'
    }

    def 'should ack by dropping the received message' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = new LocalWorkQueue()
        queue.init(id1)
        queue.offer(id1, 'alpha')

        when:
        def lease = queue.receive(id1)
        queue.ack(id1, lease.id())
        then:
        // ack is a no-op (already removed on receive) and nothing is redelivered
        queue.length(id1) == 0
        queue.receive(id1) == null
    }

    // default consume() acks on true (message gone) / releases on false (redelivered)
    def 'should ack on true and release on false via default consume()' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = new LocalWorkQueue()
        queue.init(id1)
        queue.offer(id1, 'keep-me')

        when: 'consumer returns false -> message is released and stays available'
        def r1 = queue.consume(id1, { it -> false })
        then:
        !r1
        queue.length(id1) == 1

        when: 'consumer returns true -> message is acked and removed'
        def r2 = queue.consume(id1, { it -> it == 'keep-me' })
        then:
        r2
        queue.length(id1) == 0
        and:
        // nothing left to consume
        !queue.consume(id1, { it -> assert false /* not invoked */ })
    }

}
