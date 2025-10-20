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

package io.seqera.cache.tiered

import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration

import io.micronaut.context.ApplicationContext
import io.seqera.fixtures.redis.RedisTestContainer

class RedisL2TieredCacheTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should get and put a key-value pair with ttl' () {
        given:
        def cache = context.getBean(RedisL2TieredCache)
        def TTL = Duration.ofMillis(100)
        def k = UUID.randomUUID().toString()

        expect:
        cache.get(k) == null

        when:
        cache.put(k, "hello", TTL)
        then:
        cache.get(k) == 'hello'
        then:
        sleep(TTL.toMillis() *2)
        and:
        cache.get(k) == null
    }

}
