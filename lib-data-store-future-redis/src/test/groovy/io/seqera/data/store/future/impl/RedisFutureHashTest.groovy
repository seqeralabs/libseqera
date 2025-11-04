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

package io.seqera.data.store.future.impl

import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration

import io.micronaut.context.ApplicationContext
import io.seqera.fixtures.redis.RedisTestContainer

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisFutureHashTest extends Specification implements RedisTestContainer  {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should set and get a value' () {
        given:
        def queue = context.getBean(RedisFutureHash)

        expect:
        queue.take('xyz') == null

        when:
        queue.put('xyz', 'hello', Duration.ofSeconds(5))
        then:
        queue.take('xyz') == 'hello'
        and:
        queue.take('xyz') == null
    }

    def 'should validate expiration' () {
        given:
        def uid = UUID.randomUUID().toString()
        def queue = context.getBean(RedisFutureHash)

        when:
        queue.put(uid, 'foo', Duration.ofMillis(500))
        then:
        queue.take(uid) == 'foo'

        when:
        queue.put(uid, 'bar', Duration.ofMillis(100))
        and:
        sleep 500
        then:
        queue.take(uid) == null
    }
}
