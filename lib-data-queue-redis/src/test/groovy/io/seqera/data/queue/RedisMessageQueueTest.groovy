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

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

import io.micronaut.context.ApplicationContext
import io.seqera.data.queue.impl.RedisMessageQueue
import io.seqera.fixtures.redis.RedisTestContainer
import spock.lang.Shared
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisMessageQueueTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    private static String rndHex() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong())
    }

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should return null if empty' () {
        given:
        def broker = context.getBean(RedisMessageQueue)

        expect:
        broker.poll('foo') == null

        when:
        def start = System.currentTimeMillis()
        and:
        broker.poll('foo', Duration.ofMillis(500)) == null
        and:
        def delta = System.currentTimeMillis()-start
        then:
        assert delta>500
        assert delta<1000
    }

    def 'should offer and poll a value' () {
        given:
        def broker = context.getBean(RedisMessageQueue)
        and:
        broker.offer('bar', 'alpha')
        broker.offer('bar', 'beta')

        expect:
        broker.poll('foo') == null
        broker.poll('bar') == 'alpha'
        broker.poll('bar') == 'beta'
    }

    def 'should offer and poll a value after wait' () {
        given:
        def queue = context.getBean(RedisMessageQueue)
        def wait = Duration.ofMillis(500)
        and:
        queue.offer('bar1', 'alpha1')
        queue.offer('bar1', 'beta1')

        expect:
        queue.poll('foo1', wait) == null
        queue.poll('bar1', wait) == 'alpha1'
        queue.poll('bar1', wait) == 'beta1'
    }

    def 'should validate queue length' () {
        given:
        def id1 = "queue-${rndHex()}"
        def id2 = "queue-${rndHex()}"
        and:
        def queue = context.getBean(RedisMessageQueue)

        expect:
        queue.length('foo') == 0

        when:
        queue.offer(id1, 'one')
        queue.offer(id1, 'two')
        queue.offer(id2, 'three')
        then:
        queue.length(id1) == 2
        queue.length(id2) == 1

        when:
        queue.poll(id1)
        then:
        queue.length(id1) == 1
    }
}
