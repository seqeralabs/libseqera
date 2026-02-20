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

package io.seqera.data.count

import spock.lang.Shared
import spock.lang.Specification

import io.micronaut.context.ApplicationContext
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.data.count.impl.RedisCountProvider
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisCountProviderTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext applicationContext

    @Shared
    RedisCountProvider provider

    def setup() {
        applicationContext = ApplicationContext.run('test', 'redis')
        provider = applicationContext.getBean(RedisCountProvider)
        sleep(500) // workaround to wait for Redis connection
    }

    def cleanup() {
        applicationContext.close()
    }

    def 'should increment counter' () {
        expect:
        provider.increment('foo', 1) == 1
        and:
        provider.increment('foo', 1) == 2
        and:
        provider.increment('foo', 5) == 7
        and:
        provider.get('foo') == 7
    }

    def 'should decrement counter' () {
        expect:
        provider.decrement('bar', 1) == -1
        and:
        provider.decrement('bar', 1) == -2
        and:
        provider.decrement('bar', 3) == -5
        and:
        provider.get('bar') == -5
    }

    def 'should get zero for missing key' () {
        expect:
        provider.get('missing') == 0
    }

    def 'should handle multiple keys' () {
        when:
        provider.increment('key1', 10)
        provider.increment('key2', 20)

        then:
        provider.get('key1') == 10
        and:
        provider.get('key2') == 20
    }

    def 'should clear counter' () {
        when:
        provider.increment('clr', 10)
        and:
        provider.clear('clr')

        then:
        provider.get('clr') == 0
    }

    def 'should handle increment and decrement together' () {
        expect:
        provider.increment('counter', 10) == 10
        and:
        provider.decrement('counter', 3) == 7
        and:
        provider.increment('counter', 5) == 12
        and:
        provider.decrement('counter', 15) == -3
        and:
        provider.get('counter') == -3
    }
}
