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

package io.seqera.data.store.state.impl

import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.data.store.state.CountParams
import jakarta.inject.Inject

@MicronautTest(environments = ['test'])
class LocalStateProviderTest extends Specification {

    @Inject
    LocalStateProvider provider


    def 'should get and put a key-value pair' () {
        given:
        def k = UUID.randomUUID().toString()

        expect:
        provider.get(k) == null

        when:
        provider.put(k, "hello")
        then:
        provider.get(k) == 'hello'
    }

    def 'should get and put a key-value pair with ttl' () {
        given:
        def TTL = 100
        def k = UUID.randomUUID().toString()

        expect:
        provider.get(k) == null

        when:
        provider.put(k, "hello", Duration.ofMillis(TTL))
        then:
        provider.get(k) == 'hello'
        then:
        sleep(TTL *2)
        and:
        provider.get(k) == null
    }

    def 'should get and put only if absent' () {
        given:
        def k = UUID.randomUUID().toString()

        expect:
        provider.get(k) == null

        when:
        def done = provider.putIfAbsent(k, 'foo')
        then:
        done 
        and:
        provider.get(k) == 'foo'

        when:
        done = provider.putIfAbsent(k, 'bar')
        then:
        !done
        and:
        provider.get(k) == 'foo'
    }

    def 'should get and put if absent with ttl' () {
        given:
        def TTL = 100
        def k = UUID.randomUUID().toString()

        when:
        def done = provider.putIfAbsent(k, 'foo', Duration.ofMillis(TTL))
        then:
        done
        and:
        provider.get(k) == 'foo'

        when:
        done = provider.putIfAbsent(k, 'bar', Duration.ofMillis(TTL))
        then:
        !done
        and:
        provider.get(k) == 'foo'

        when:
        sleep(TTL *2)
        and:
        done = provider.putIfAbsent(k, 'bar', Duration.ofMillis(TTL))
        then:
        done
        and:
        provider.get(k) == 'bar'
    }

    def 'should get and put if absent and increment' () {
        given:
        def ttlMillis = 100
        def k = UUID.randomUUID().toString()
        def c = CountParams .of(UUID.randomUUID().toString())
        def luaScript1 = /string.gsub(value, '"count"%s*:%s*(%d+)', '"count":' .. counter_value)/
        def luaScript2 = /string.gsub(value, '"count"%s*:%s*"(.-)(%d+)"', '"count":"%1' .. counter_value .. '"')/

        expect:
        provider.get(k) == null

        when:
        def result = provider.putJsonIfAbsentAndIncreaseCount(k, '{"foo":"x","count":0}', Duration.ofMillis(ttlMillis), c, luaScript1)
        then:
        result.succeed
        result.value == '{"foo":"x","count":1}'
        result.count == 1
        and:
        provider.get(k) == '{"foo":"x","count":1}'

        when:
        result = provider.putJsonIfAbsentAndIncreaseCount(k, '{"bar":"y","count":0}', Duration.ofMillis(ttlMillis), c, luaScript1)
        then:
        !result.succeed
        result.value == '{"foo":"x","count":1}'
        result.count == 1
        and:
        provider.get(k) == '{"foo":"x","count":1}'

        when:
        sleep(ttlMillis *2)
        and:
        result = provider.putJsonIfAbsentAndIncreaseCount(k, '{"bar":"y","count":0}', Duration.ofMillis(ttlMillis), c, luaScript1)
        then:
        result.succeed
        result.value == '{"bar":"y","count":2}'
        result.count == 2

        when:
        sleep(ttlMillis *2)
        and:
        result = provider.putJsonIfAbsentAndIncreaseCount(k, '{"bar":"y", "count":"xx-a1b2c3_100"}', Duration.ofMillis(ttlMillis), c, luaScript2)
        then:
        result.succeed
        result.value == '{"bar":"y", "count":"xx-a1b2c3_3"}'
        result.count == 3

    }

    def 'should put and remove a value' () {
        given:
        def TTL = 100
        def k = UUID.randomUUID().toString()

        when:
        provider.put(k, 'foo')
        then:
        provider.get(k) == 'foo'

        when:
        provider.remove(k)
        then:
        provider.get(k) == null
    }

    def 'should replace a value only when the stored version matches' () {
        given:
        def k = UUID.randomUUID().toString()

        expect: 'a missing key is not replaced'
        !provider.replaceIf(k, 0, '{"@v":1,"x":"a"}', Duration.ofSeconds(10))

        when:
        provider.put(k, '{"@v":3,"x":"a"}')
        then: 'a mismatching version is not replaced'
        !provider.replaceIf(k, 2, '{"@v":3,"x":"b"}', Duration.ofSeconds(10))
        provider.get(k) == '{"@v":3,"x":"a"}'

        and: 'a matching version is replaced'
        provider.replaceIf(k, 3, '{"@v":4,"x":"b"}', Duration.ofSeconds(10))
        provider.get(k) == '{"@v":4,"x":"b"}'
    }

    def 'should treat a value without version frame as version zero' () {
        given:
        def k = UUID.randomUUID().toString()
        provider.put(k, '{"x":"legacy"}')

        expect: 'a non-zero version does not match an unframed value'
        !provider.replaceIf(k, 7, '{"@v":8,"x":"a"}', Duration.ofSeconds(10))

        and: 'version zero adopts it'
        provider.replaceIf(k, 0, '{"@v":1,"x":"a"}', Duration.ofSeconds(10))
        provider.get(k) == '{"@v":1,"x":"a"}'
    }

    def 'should parse the version of a non-canonical head numerically' () {
        given:
        def k1 = UUID.randomUUID().toString()
        def k2 = UUID.randomUUID().toString()
        def k3 = UUID.randomUUID().toString()
        def k4 = UUID.randomUUID().toString()

        when: 'a head with leading-zero version digits'
        provider.put(k1, '{"@v":007,"x":"a"}')
        then: 'the version is the numeric value of the digits'
        !provider.replaceIf(k1, 0, '{"@v":8,"x":"b"}', Duration.ofSeconds(10))
        provider.replaceIf(k1, 7, '{"@v":8,"x":"b"}', Duration.ofSeconds(10))
        provider.get(k1) == '{"@v":8,"x":"b"}'

        when: 'a head whose 19-digit version exceeds the long range'
        provider.put(k2, '{"@v":9999999999999999999,"x":"a"}')
        then: 'it counts as version zero and is adopted by it'
        !provider.replaceIf(k2, 9, '{"@v":1,"x":"b"}', Duration.ofSeconds(10))
        provider.replaceIf(k2, 0, '{"@v":1,"x":"b"}', Duration.ofSeconds(10))
        provider.get(k2) == '{"@v":1,"x":"b"}'

        when: 'a head whose version digits overflow a long'
        provider.put(k3, '{"@v":99999999999999999999,"x":"a"}')
        then: 'it counts as version zero and is adopted by it'
        provider.replaceIf(k3, 0, '{"@v":1,"x":"b"}', Duration.ofSeconds(10))
        provider.get(k3) == '{"@v":1,"x":"b"}'

        when: 'a head carrying the largest version a store can write'
        provider.put(k4, '{"@v":9223372036854775807,"x":"a"}')
        then: 'it still matches exactly'
        provider.replaceIf(k4, Long.MAX_VALUE, '{"@v":1,"x":"b"}', Duration.ofSeconds(10))
        provider.get(k4) == '{"@v":1,"x":"b"}'
    }

    def 'should reset the ttl on versioned replace' () {
        given:
        def TTL = 600
        def HALF = Math.round(TTL * 0.66)
        def k = UUID.randomUUID().toString()

        when: 'an entry near its deadline is replaced'
        provider.put(k, '{"@v":1,"x":"a"}', Duration.ofMillis(TTL))
        sleep(HALF)
        provider.replaceIf(k, 1, '{"@v":2,"x":"b"}', Duration.ofMillis(TTL))
        and:
        sleep(HALF)
        then: 'the entry is still alive past the original deadline'
        provider.get(k) == '{"@v":2,"x":"b"}'
    }

    def 'should let exactly one concurrent replace succeed' () {
        given:
        def THREADS = 16
        def ROUNDS = 10
        def pool = Executors.newFixedThreadPool(THREADS)

        when:
        def winsPerRound = (1..ROUNDS).collect { round ->
            final k = UUID.randomUUID().toString()
            provider.put(k, '{"@v":0,"x":"v0"}')
            final barrier = new CyclicBarrier(THREADS)
            final futures = (0..<THREADS).collect { i ->
                pool.submit({
                    barrier.await()
                    provider.replaceIf(k, 0, '{"@v":1,"x":"w' + i + '"}', Duration.ofSeconds(10))
                } as Callable<Boolean>)
            }
            return futures.count { it.get() } as int
        }

        then: 'exactly one writer wins every round'
        winsPerRound == [1] * ROUNDS

        cleanup:
        pool.shutdown()
    }

}
