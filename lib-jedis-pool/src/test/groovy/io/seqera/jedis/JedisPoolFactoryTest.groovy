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
package io.seqera.jedis

import spock.lang.Specification

import io.micrometer.core.instrument.MeterRegistry
import redis.clients.jedis.exceptions.InvalidURIException

/**
 * Tests for {@link JedisPoolFactory}
 *
 * @author Paolo Di Tommaso
 */
class JedisPoolFactoryTest extends Specification {

    def 'should create redis pool with valid URI'() {
        given:
        def factory = new JedisPoolFactory(meterRegistry: Mock(MeterRegistry))

        when:
        def pool = factory.createRedisPool(URI_STRING, MIN_IDLE, MAX_IDLE, MAX_TOTAL, TIMEOUT, 'password')

        then:
        pool != null

        cleanup:
        pool?.close()

        where:
        URI_STRING                       | MIN_IDLE | MAX_IDLE | MAX_TOTAL | TIMEOUT
        'redis://localhost:6379'         | 0        | 10       | 50        | 5000
        'redis://localhost:6379/0'       | 0        | 10       | 50        | 5000
        'redis://localhost:6379/1'       | 1        | 5        | 20        | 3000
        'redis://localhost:6379/15'      | 0        | 10       | 50        | 5000
        'rediss://localhost:6379'        | 1        | 5        | 20        | 3000
        'rediss://localhost:6379/2'      | 1        | 5        | 20        | 3000
    }

    def 'should parse database index from URI'() {
        given:
        def factory = new JedisPoolFactory()

        when:
        def clientConfig = factory.clientConfig(URI.create(URI_STRING), null, 5000)

        then:
        clientConfig.database == EXPECTED_DB

        where:
        URI_STRING                   | EXPECTED_DB
        'redis://localhost:6379'     | 0
        'redis://localhost:6379/0'   | 0
        'redis://localhost:6379/1'   | 1
        'redis://localhost:6379/5'   | 5
        'redis://localhost:6379/15'  | 15
    }

    def 'should throw exception for invalid URI'() {
        given:
        def factory = new JedisPoolFactory(meterRegistry: Mock(MeterRegistry))

        when:
        factory.createRedisPool(URI_STRING, 0, 10, 50, 5000, null)

        then:
        def e = thrown(InvalidURIException)
        e.message.contains("Invalid Redis connection URI")

        where:
        URI_STRING          | _
        'redis://localhost' | _
        'localhost:6379'    | _
    }

    def 'should create pool without meter registry'() {
        given:
        def factory = new JedisPoolFactory()

        when:
        def pool = factory.createRedisPool('redis://localhost:6379', 0, 10, 50, 5000, null)

        then:
        pool != null

        cleanup:
        pool?.close()
    }
}
