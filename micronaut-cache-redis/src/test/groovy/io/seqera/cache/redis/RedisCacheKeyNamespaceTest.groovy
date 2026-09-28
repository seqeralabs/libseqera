/*
 * Copyright 2026, Seqera Labs
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
package io.seqera.cache.redis

import io.micronaut.context.ApplicationContext
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.inject.qualifiers.Qualifiers
import io.seqera.fixtures.redis.RedisTestContainer
import spock.lang.Specification

/**
 * A cache's keys live in the {@code <cacheName>:} namespace, and {@code keys()} / {@code invalidateAll()}
 * find them with {@code SCAN MATCH}, a glob: the cache name must match literally and must not overlap
 * another cache's namespace.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisCacheKeyNamespaceTest extends Specification implements RedisTestContainer {

    def 'the SCAN pattern matches the cache name literally: #NAME'() {
        expect:
        RedisCache.keysPattern(NAME) == PATTERN

        where:
        NAME            | PATTERN
        'plain-name-v1' | 'plain-name-v1:*'
        'user?'         | 'user\\?:*'
        'user*'         | 'user\\*:*'
        'tokens[1]'     | 'tokens\\[1\\]:*'
        'back\\slash'   | 'back\\\\slash:*'
    }

    def 'a cache whose name holds the glob character in #NAME lists and clears only its own keys'() {
        given:
        def context = ApplicationContext.run([
                ("redis.caches.${NAME}.expire-after-write".toString()): '1h',
                'redis.caches.users.expire-after-write': '1h'
        ], 'test')
        def cache = context.getBean(RedisCache, Qualifiers.byName(NAME))
        def users = context.getBean(RedisCache, Qualifiers.byName('users'))
        users.put('alice', 'a')
        cache.put('own', 'x')

        expect: 'the look-alike cache "users" is neither listed nor cleared'
        cache.keys() == ['own'] as Set
        cache.invalidateAll()
        cache.keys().isEmpty()
        users.get('alice', String).get() == 'a'

        cleanup:
        users?.invalidateAll()
        context?.close()

        where:
        NAME << ['user?', 'user*']
    }

    def 'a cache name with a colon is refused, since its keys would fall in another cache\'s namespace'() {
        given:
        def context = ApplicationContext.run(['redis.caches.foo:bar.expire-after-write': '1h'], 'test')

        when:
        context.getBean(RedisCache, Qualifiers.byName('foo:bar'))

        then:
        def e = thrown(BeanInstantiationException)
        e.message.contains("must not contain ':'")

        cleanup:
        context?.close()
    }
}
