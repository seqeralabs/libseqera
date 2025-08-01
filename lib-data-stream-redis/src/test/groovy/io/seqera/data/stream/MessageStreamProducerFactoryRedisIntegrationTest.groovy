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

import io.micronaut.context.ApplicationContext
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.data.stream.impl.RedisMessageStream
import spock.lang.Shared
import spock.lang.Specification

/**
 * Integration tests for MessageStreamProducerFactory with Redis backend
 *
 * @author Ramon Amela <ramon.amela@gmail.com>
 */
class MessageStreamProducerFactoryRedisIntegrationTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should inject factory with Redis message stream'() {
        given:
        def factory = context.getBean(MessageStreamProducerFactory)
        def redisMessageStream = context.getBean(RedisMessageStream)

        expect:
        factory != null
        redisMessageStream != null
    }

    def 'should create producer that works with Redis backend'() {
        given:
        def redisMessageStream = context.getBean(RedisMessageStream)
        def initialLength = redisMessageStream.length(TestMessage.TOPIC_ID)
        def factory = new MessageStreamProducerFactory(redisMessageStream)
        def producer = factory.createProducer()
        def testMessage = new TestMessage('redis-x', 'redis-y')

        when:
        producer.offer(testMessage)

        then:
        producer != null
        redisMessageStream.length(TestMessage.TOPIC_ID) == initialLength + 1
    }

    def 'should persist messages in Redis stream'() {
        given:
        def redisMessageStream = context.getBean(RedisMessageStream)
        def initialLength = redisMessageStream.length(TestMessage.TOPIC_ID)
        def factory = new MessageStreamProducerFactory(redisMessageStream)
        def producer = factory.createProducer()
        def message1 = new TestMessage('persist-1', 'data-1')
        def message2 = new TestMessage('persist-2', 'data-2')

        when:
        producer.offer(message1)
        producer.offer(message2)

        then:
        redisMessageStream.length(TestMessage.TOPIC_ID) == initialLength + 2
    }

    def 'should create producer with correct generic typing'() {
        given:
        def redisMessageStream = context.getBean(RedisMessageStream)
        def initialLength = redisMessageStream.length(TestMessage.TOPIC_ID)
        def factory = new MessageStreamProducerFactory(redisMessageStream)

        when:
        MessageStreamProducer<TestMessage> producer = factory.createProducer()
        def testMessage = new TestMessage('typed-x', 'typed-y')
        producer.offer(testMessage)

        then:
        producer != null
        redisMessageStream.length(TestMessage.TOPIC_ID) == initialLength + 1
    }
}