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

import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.random.LongRndKey
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.ArrayBlockingQueue

import io.micronaut.context.ApplicationContext
import io.seqera.data.stream.impl.RedisMessageStream

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class MessageStreamProducerRedisTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should offer and consume some messages' () {
        given:
        def target = context.getBean(RedisMessageStream)
        def factory = new MessageStreamProducerFactory(target)
        def producer = factory.createProducer()

        when:
        producer.offer(new TestMessage('one','two'))
        producer.offer(new TestMessage('alpha','omega'))
        then:
        target.length(TestMessage.TOPIC_ID)==2

    }

}
