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

package io.seqera.data.queue

import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import io.micronaut.context.ApplicationContext
import io.seqera.data.queue.impl.RedisMessageQueue
import io.seqera.fixtures.redis.RedisTestContainer

/**
 * Test class {@link AbstractMessageQueue} using a {@link RedisMessageQueue}
 *
 * @author Jordi Deu-Pons <jordi@seqera.io>
 */
class AbstractMessageQueueRedisTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should send and consume a request'() {
        given:
        def executor = Executors.newCachedThreadPool()
        def broker = context.getBean(RedisMessageQueue)
        def queue = new TestMsgQueue(broker, executor) .start()
        and:
        def result = new CompletableFuture<TestMsg>()
        when:
        queue.registerClient('service-key-two', '123', { result.complete(it) })
        and:
        queue.offer('service-key-two', new TestMsg('xyz'))
        then:
        result.get(1,TimeUnit.SECONDS).value == 'xyz'

        cleanup:
        queue.close()
    }


    def 'should send and consume a request across instances'() {
        given:
        def executor = Executors.newCachedThreadPool()
        def broker1 = context.getBean(RedisMessageQueue)
        def queue1 = new TestMsgQueue(broker1, executor) .start()
        and:
        def broker2 = context.getBean(RedisMessageQueue)
        def queue2 = new TestMsgQueue(broker2, executor) .start()
        and:
        def result = new CompletableFuture<TestMsg>()

        when:
        queue2.registerClient('service-key-three', '123', { result.complete(it) })
        and:
        queue1.offer('service-key-three', new TestMsg('123'))
        then:
        result.get(1,TimeUnit.SECONDS).value == '123'

        cleanup:
        queue1.close()
        queue2.close()
    }

}
