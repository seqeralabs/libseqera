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

import io.seqera.random.LongRndKey
import spock.lang.Specification

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.data.stream.impl.LocalMessageStream

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class LocalMessageStreamTest extends Specification {

    def 'should offer and consume a value' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def id2 = "stream-${LongRndKey.rndHex()}"
        and:
        def stream = new LocalMessageStream()
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
        stream.consume(id1, { it-> it=='one'})
        and:
        stream.consume(id2, { it-> it=='alpha'})
        stream.consume(id2, { it-> it=='delta'})
        stream.consume(id2, { it-> it=='gamma'})
        and:
        !stream.consume(id2, { it-> assert false /* <-- this should not be invoked */ })
    }

    def 'should offer and consume a value with a failure' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalMessageStream()
        stream.init(id1)
        when:
        stream.offer(id1, 'alpha')
        stream.offer(id1, 'delta')
        stream.offer(id1, 'gamma')

        then:
        stream.consume(id1, { it-> it=='alpha'})
        and:
        // the default consume() does not catch handler exceptions: it propagates and,
        // since poll() already removed 'delta' and release() is not reached, it is dropped
        try {
            stream.consume(id1, { it-> throw new RuntimeException("Oops")})
            assert false
        }
        catch (RuntimeException e) {
            assert e.message == 'Oops'
        }
        and:
        // next message is 'gamma' as expected ('delta' was dropped on the throw)
        stream.consume(id1, { it-> it=='gamma'})
        and:
        !stream.consume(id1, { it-> assert false /* <-- this should not be invoked */ })

        when:
        stream.offer(id1, 'something')
        then:
        stream.consume(id1, { it-> it=='something'})
    }

    def 'should validate length method' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalMessageStream()
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
        stream.consume(id1, { it-> true})
        then:
        stream.length(id1) == 2
    }

    // §10.10 — Local backend: poll returns a lease; renew is a no-op; release re-offers
    def 'should poll and release re-offering the message' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalMessageStream()
        stream.init(id1)
        stream.offer(id1, 'alpha')

        when: 'poll takes the message off the queue (lease id == message value)'
        def lease = stream.poll(id1)
        then:
        lease != null
        lease.message() == 'alpha'
        stream.length(id1) == 0

        when: 'renew is a no-op and does not throw nor alter the queue'
        stream.renew(id1, lease.id())
        then:
        stream.length(id1) == 0

        when: 'release re-offers the message for later redelivery'
        stream.release(id1, lease.id())
        then:
        stream.length(id1) == 1
        stream.poll(id1).message() == 'alpha'
    }

    def 'should ack by dropping the polled message' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalMessageStream()
        stream.init(id1)
        stream.offer(id1, 'alpha')

        when:
        def lease = stream.poll(id1)
        stream.ack(id1, lease.id())
        then:
        // ack is a no-op (already removed on poll) and nothing is redelivered
        stream.length(id1) == 0
        stream.poll(id1) == null
    }

    // §10.11 — default consume() acks on true (message gone) / releases on false (redelivered)
    def 'should ack on true and release on false via default consume()' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = new LocalMessageStream()
        stream.init(id1)
        stream.offer(id1, 'keep-me')

        when: 'consumer returns false -> message is released and stays available'
        def r1 = stream.consume(id1, { it -> false })
        then:
        !r1
        stream.length(id1) == 1

        when: 'consumer returns true -> message is acked and removed'
        def r2 = stream.consume(id1, { it -> it == 'keep-me' })
        then:
        r2
        stream.length(id1) == 0
        and:
        // nothing left to consume
        !stream.consume(id1, { it -> assert false /* not invoked */ })
    }

}
