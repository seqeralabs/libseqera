/*
 * Copyright 2025, Seqera Labs
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
        !stream.consume(id1, { it-> throw new RuntimeException("Oops")})
        and:
        // next message is 'gamma' as expected
        stream.consume(id1, { it-> it=='gamma'})
        and:
        // now the errored message is available again
        stream.consume(id1, { it-> it=='delta'})
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

}
