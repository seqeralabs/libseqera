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

package io.seqera.nodeid

import io.seqera.fixtures.redis.RedisTestContainer
import redis.clients.jedis.JedisPool
import spock.lang.Specification

class RedisNodeIdTest extends Specification implements RedisTestContainer {

    JedisPool getJedisPool() {
        new JedisPool(getRedisHostName(), getRedisPort() as int)
    }

    def 'should assign rotating ordinals from a shared counter'() {
        given:
        def pool = jedisPool

        when:
        def first = new RedisNodeId('test-rotate', 1024, pool)
        def second = new RedisNodeId('test-rotate', 1024, pool)

        then:
        first.value() == 0
        second.value() == 1
    }

    def 'should rotate the ordinal modulo capacity'() {
        given:
        def pool = jedisPool

        when: 'more instances start than the capacity'
        def values = (1..3).collect { new RedisNodeId('test-wrap', 2, pool).value() }

        then: 'ordinals wrap around the capacity'
        values == [0, 1, 0]
    }

    def 'should keep the stored counter bounded below capacity'() {
        given:
        def pool = jedisPool

        when: 'many more instances start than the capacity'
        (1..20).each { new RedisNodeId('test-bounded', 4, pool) }

        then: 'the stored counter is wrapped server-side and never grows past capacity'
        def stored = null
        pool.resource.withCloseable { j -> stored = j.get('test-bounded:node-id:counter') }
        stored != null
        (stored as int) < 4
    }

    def 'should fall back to a random ordinal when Redis is unreachable'() {
        given: 'a pool pointing at a port with no Redis'
        def deadPool = new JedisPool('127.0.0.1', 6390)

        when:
        def nodeId = new RedisNodeId('test-fallback', 16, deadPool)

        then: 'a valid ordinal is assigned without throwing'
        nodeId.value() >= 0
        nodeId.value() < 16

        cleanup:
        deadPool?.close()
    }
}
