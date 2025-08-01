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

import io.seqera.data.stream.impl.LocalMessageStream
import io.seqera.random.LongRndKey
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Tests for MessageStreamProducerFactory
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class MessageStreamProducerFactoryTest extends Specification {

    def 'should create a producer instance'() {
        given:
        LocalMessageStream messageStream = new LocalMessageStream()
        and:
        def factory = new MessageStreamProducerFactory(messageStream)

        when:
        def producer = factory.createProducer()

        then:
        producer != null
        producer instanceof MessageStreamProducer
    }

    def 'should create producer that can offer messages'() {
        given:
        LocalMessageStream messageStream = new LocalMessageStream()
        and:
        def factory = new MessageStreamProducerFactory(messageStream)
        def producer = factory.createProducer()

        when:
        producer.offer(new TestMessage('test-x', 'test-y'))

        then:
        messageStream.length(TestMessage.TOPIC_ID) == 1
    }

    def 'should create producer that uses topic encoding strategy'() {
        given:
        LocalMessageStream messageStream = new LocalMessageStream()
        and:
        def factory = new MessageStreamProducerFactory(messageStream)
        def producer = factory.createProducer()
        def testMessage = new TestMessage('encoded-x', 'encoded-y')

        when:
        producer.offer(testMessage)
        def encodedMessage = messageStream.delegate.get(TestMessage.TOPIC_ID).poll()

        then:
        encodedMessage != null
        encodedMessage.contains('"x":"encoded-x"')
        encodedMessage.contains('"y":"encoded-y"')
    }

    def 'should handle multiple topics with same producer'() {
        given:
        LocalMessageStream messageStream = new LocalMessageStream()
        and:
        def factory = new MessageStreamProducerFactory(messageStream)
        def producer = factory.createProducer()

        when:
        producer.offer(new TestMessage('msg1-x', 'msg1-y'))
        producer.offer(new TestMessage2('msg2-x', 'msg2-y'))

        then:
        messageStream.length(TestMessage.TOPIC_ID) == 1
        messageStream.length(TestMessage2.TOPIC_ID) == 1
    }
}
