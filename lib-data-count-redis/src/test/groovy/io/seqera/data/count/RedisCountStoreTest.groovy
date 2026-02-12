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
class RedisCountStoreTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext applicationContext

    @Shared
    RedisCountProvider provider

    AbstractCountStore create(String prefix) {
        return new AbstractCountStore(provider) {
            @Override
            protected String getPrefix() { return prefix }
        }
    }

    def setup() {
        applicationContext = ApplicationContext.run('test', 'redis')
        provider = applicationContext.getBean(RedisCountProvider)
        sleep(500) // workaround to wait for Redis connection
    }

    def cleanup() {
        applicationContext.close()
    }

    def 'should increment with prefix' () {
        given:
        def store = create('test')

        expect:
        store.increment('a') == 1
        and:
        store.increment('a') == 2
        and:
        store.increment('a', 5) == 7
        and:
        store.get('a') == 7
    }

    def 'should decrement with prefix' () {
        given:
        def store = create('dec')

        expect:
        store.decrement('a') == -1
        and:
        store.decrement('a') == -2
        and:
        store.decrement('a', 3) == -5
        and:
        store.get('a') == -5
    }

    def 'should get zero for missing key' () {
        given:
        def store = create('test')

        expect:
        store.get('missing') == 0
    }

    def 'should isolate keys by prefix' () {
        given:
        def store1 = create('pfx1')
        def store2 = create('pfx2')

        when:
        store1.increment('x', 10)
        store2.increment('x', 20)

        then:
        store1.get('x') == 10
        and:
        store2.get('x') == 20
    }

    def 'should clear counter' () {
        given:
        def store = create('clr')

        when:
        store.increment('a', 10)
        and:
        store.clear('a')

        then:
        store.get('a') == 0
    }

    def 'should handle increment and decrement together' () {
        given:
        def store = create('mix')

        expect:
        store.increment('a', 10) == 10
        and:
        store.decrement('a', 3) == 7
        and:
        store.increment('a', 5) == 12
        and:
        store.decrement('a', 15) == -3
        and:
        store.get('a') == -3
    }

    def 'should handle multiple keys within same store' () {
        given:
        def store = create('multi')

        when:
        store.increment('x', 10)
        store.increment('y', 20)

        then:
        store.get('x') == 10
        and:
        store.get('y') == 20
    }
}
