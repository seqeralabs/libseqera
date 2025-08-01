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

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

/**
 * Integration tests for MessageStreamProducerFactory with Micronaut DI
 *
 * @author Ramon Amela <ramon.amela@gmail.com>
 */
@MicronautTest(environments = ["test"])
class MessageStreamProducerFactoryIntegrationTest extends Specification {

    @Inject
    MessageStreamProducerFactory factory

    @Inject
    MessageStream<String> messageStream

    def 'should inject MessageStreamProducerFactory via Micronaut DI'() {
        expect:
        factory != null
        messageStream != null
    }

    def 'should create functional producer through injected factory'() {
        given:
        def initialLengthTest1 = messageStream.length(TestMessage.TOPIC_ID)
        def producer = factory.createProducer()
        def testMessage = new TestMessage('integration-x', 'integration-y')

        when:
        producer.offer(testMessage)

        then:
        producer != null
        producer instanceof MessageStreamProducer
        messageStream.length(TestMessage.TOPIC_ID) == initialLengthTest1 + 1
    }

    def 'should create producer with correct generic typing'() {
        given:
        def initialLengthTest1 = messageStream.length(TestMessage.TOPIC_ID)
        def producer = factory.createProducer()

        when:
        MessageStreamProducer<TestMessage> producer2 = factory.createProducer()
        def testMessage = new TestMessage('typed-x', 'typed-y')
        producer2.offer(testMessage)

        then:
        producer != null
        producer2 != null
        messageStream.length(TestMessage.TOPIC_ID) == initialLengthTest1 + 1
    }

    def 'should work with different message types'() {
        given:
        def initialLengthTest1 = messageStream.length(TestMessage.TOPIC_ID)
        def initialLengthTest2 = messageStream.length(TestMessage2.TOPIC_ID)

        MessageStreamProducer<TestMessage> producer1 = factory.createProducer()
        MessageStreamProducer<TestMessage2> producer2 = factory.createProducer()

        when:
        producer1.offer(new TestMessage('test1-x', 'test1-y'))
        producer2.offer(new TestMessage2('test2-x', 'test2-y'))

        then:
        messageStream.length(TestMessage.TOPIC_ID) == initialLengthTest1 + 1
        messageStream.length(TestMessage2.TOPIC_ID) == initialLengthTest2 + 1
    }
}
