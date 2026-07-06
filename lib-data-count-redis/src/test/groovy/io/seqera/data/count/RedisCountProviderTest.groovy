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

package io.seqera.data.count

import java.util.concurrent.Callable
import java.util.concurrent.Executors

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

    def 'tryAcquire admits up to the limit and rejects beyond it (Lua undoes the increment)' () {
        expect:
        provider.tryAcquire('acq', 6, 8, 0)
        provider.get('acq') == 6

        and: 'over-limit rejected, counter unchanged'
        !provider.tryAcquire('acq', 4, 8, 0)
        provider.get('acq') == 6

        and: 'exact-fit admitted'
        provider.tryAcquire('acq', 2, 8, 0)
        provider.get('acq') == 8
    }

    def 'tryAcquire keys are independent' () {
        expect:
        provider.tryAcquire('acqA', 8, 8, 0)
        !provider.tryAcquire('acqA', 1, 8, 0)
        provider.tryAcquire('acqB', 8, 8, 0)
    }

    def 'tryAcquire sets a TTL on a freshly-created key' () {
        given:
        def pool = applicationContext.getBean(redis.clients.jedis.JedisPool)

        when:
        provider.tryAcquire('acqTtl', 1, 8, 1234)
        final ttl = pool.resource.withCloseable { conn -> conn.ttl('acqTtl') }

        then:
        ttl > 0
        ttl <= 1234
    }

    def 'tryAcquire with non-positive ttl sets no expiry' () {
        given:
        def pool = applicationContext.getBean(redis.clients.jedis.JedisPool)

        when:
        provider.tryAcquire('acqNoTtl', 1, 8, 0)
        final ttl = pool.resource.withCloseable { conn -> conn.ttl('acqNoTtl') }

        then:
        ttl == -1L   // key exists, no expiry
    }

    def 'tryAcquire rejects negative value or limit' () {
        when:
        provider.tryAcquire('acqNeg', -1, 8, 0)
        then:
        thrown(IllegalArgumentException)

        when:
        provider.tryAcquire('acqNeg', 1, -8, 0)
        then:
        thrown(IllegalArgumentException)
    }

    def 'tryAcquire recovers when the cached script is evicted (SCRIPT FLUSH / NOSCRIPT)' () {
        given: 'a first acquire caches the script SHA server-side'
        provider.tryAcquire('acqFlush', 1, 8, 0)

        when: 'Redis forgets all scripts, then we acquire again'
        applicationContext.getBean(redis.clients.jedis.JedisPool).resource.withCloseable { it.scriptFlush() }
        def admitted = provider.tryAcquire('acqFlush', 1, 8, 0)

        then: 'the provider reloads the script transparently and still works'
        admitted
        provider.get('acqFlush') == 2
    }

    def 'concurrent tryAcquire never admits beyond the limit (atomicity)' () {
        given: 'limit 8, 100 concurrent single-unit acquires on one key'
        final int limit = 8
        final int threads = 100
        final pool = Executors.newFixedThreadPool(16)
        final tasks = (1..threads).collect { i ->
            { -> provider.tryAcquire('acqRace', 1, limit, 0) } as Callable<Boolean>
        }

        when:
        final results = pool.invokeAll(tasks)*.get()
        pool.shutdown()

        then:
        results.count { it } == limit
        provider.get('acqRace') == limit
    }
}
