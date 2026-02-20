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
import io.micronaut.core.type.Argument
import io.micronaut.inject.qualifiers.Qualifiers
import io.seqera.fixtures.redis.RedisTestContainer
import redis.clients.jedis.JedisPool
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for {@link RedisCache}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisCacheTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run([
                'redis.caches.test-cache.expire-after-write': '1h',
                'redis.caches.test-cache.invalidate-scan-count': 2
        ], 'test')
    }

    def cleanup() {
        context.stop()
    }

    def 'should create redis cache bean'() {
        when:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        then:
        cache != null
        cache.getName() == 'test-cache'
        cache.getNativeCache() instanceof JedisPool
    }

    def 'should put and get value from cache'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        when:
        cache.put("key1", new TestObject(name: "test1"))
        def result = cache.get("key1", TestObject)

        then:
        result.isPresent()
        result.get().name == "test1"
    }

    def 'should return empty optional for missing key'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        when:
        def result = cache.get("non-existent-key", TestObject)

        then:
        !result.isPresent()
    }

    def 'should put and get different types'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        when:
        cache.put("string-key", "hello")
        cache.put("int-key", 42)
        cache.put("list-key", ["a", "b", "c"])

        then:
        cache.get("string-key", String).get() == "hello"
        cache.get("int-key", Integer).get() == 42
        cache.get("list-key", Argument.listOf(String)).get() == ["a", "b", "c"]
    }

    def 'should invalidate single key'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        cache.put("key-to-delete", new TestObject(name: "delete-me"))
        cache.put("key-to-keep", new TestObject(name: "keep-me"))

        when:
        cache.invalidate("key-to-delete")

        then:
        !cache.get("key-to-delete", TestObject).isPresent()
        cache.get("key-to-keep", TestObject).isPresent()
    }

    def 'should invalidate all keys'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.put("key3", "value3")

        when:
        cache.invalidateAll()

        then:
        !cache.get("key1", String).isPresent()
        !cache.get("key2", String).isPresent()
        !cache.get("key3", String).isPresent()
    }

    def 'should invalidate all with many keys using scan'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        when: 'add 100 keys to force scan pagination'
        for (int i = 0; i < 100; i++) {
            cache.put("scan-key-${i}", new TestObject(name: "value-${i}"))
        }

        then: 'all keys should exist'
        for (int i = 0; i < 100; i++) {
            cache.get("scan-key-${i}", TestObject).isPresent()
        }

        when:
        cache.invalidateAll()

        then: 'all keys should be deleted'
        for (int i = 0; i < 100; i++) {
            !cache.get("scan-key-${i}", TestObject).isPresent()
        }
    }

    def 'should not fail when invalidating empty cache'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        when:
        cache.invalidateAll()

        then:
        noExceptionThrown()
    }

    def 'should get with supplier when key missing'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        def supplierCalled = false

        when:
        def result = cache.get("supplier-key", Argument.of(TestObject), {
            supplierCalled = true
            return new TestObject(name: "supplied")
        })

        then:
        supplierCalled
        result.name == "supplied"
        // Value should now be cached
        cache.get("supplier-key", TestObject).get().name == "supplied"
    }

    def 'should get with supplier when key exists'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        cache.put("existing-key", new TestObject(name: "existing"))
        def supplierCalled = false

        when:
        def result = cache.get("existing-key", Argument.of(TestObject), {
            supplierCalled = true
            return new TestObject(name: "should-not-be-used")
        })

        then:
        !supplierCalled
        result.name == "existing"
    }

    def 'should putIfAbsent when key missing'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        when:
        def result = cache.putIfAbsent("absent-key", new TestObject(name: "new-value"))

        then:
        !result.isPresent()
        cache.get("absent-key", TestObject).get().name == "new-value"
    }

    def 'should putIfAbsent when key exists'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        cache.put("present-key", new TestObject(name: "old-value"))

        when:
        def result = cache.putIfAbsent("present-key", new TestObject(name: "new-value"))

        then:
        result.isPresent()
        result.get().name == "old-value"
        // Value should not have changed
        cache.get("present-key", TestObject).get().name == "old-value"
    }

    def 'should work with async cache'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        def asyncCache = cache.async()

        when:
        asyncCache.put("async-key", new TestObject(name: "async-value")).get()

        then:
        asyncCache.get("async-key", TestObject).get().get().name == "async-value"

        when:
        asyncCache.invalidate("async-key").get()

        then:
        !asyncCache.get("async-key", TestObject).get().isPresent()
    }

    def 'should async invalidate all'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        def asyncCache = cache.async()
        cache.put("async-key-1", "value1")
        cache.put("async-key-2", "value2")

        when:
        asyncCache.invalidateAll().get()

        then:
        !cache.get("async-key-1", String).isPresent()
        !cache.get("async-key-2", String).isPresent()
    }

    def 'async cache should have same name and native cache'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        def asyncCache = cache.async()

        expect:
        asyncCache.getName() == cache.getName()
        asyncCache.getNativeCache() == cache.getNativeCache()
    }

    // --- Early revalidation tests ---

    def 'should not revalidate when early-revalidation-window is not configured'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))
        def supplierCallCount = new AtomicInteger(0)
        cache.put("no-revalidate-key", new TestObject(name: "original"))

        when: 'get with supplier on existing key'
        def result = cache.get("no-revalidate-key", Argument.of(TestObject), {
            supplierCallCount.incrementAndGet()
            return new TestObject(name: "refreshed")
        })

        then: 'supplier should not be called (no early revalidation configured)'
        result.name == "original"
        supplierCallCount.get() == 0
    }

    def 'shouldRevalidate returns false when not configured: #SCENARIO'() {
        given:
        def cache = context.getBean(RedisCache, Qualifiers.byName("test-cache"))

        expect:
        !cache.shouldRevalidate(REMAINING_TTL_MS)

        where:
        REMAINING_TTL_MS | SCENARIO
        0                | 'expired'
        1000             | 'mid TTL'
        -1               | 'no expiry set'
        -2               | 'key does not exist'
    }

    def 'shouldRevalidate deterministic result when configured: #SCENARIO'() {
        given: 'a cache with 5min TTL and 5min revalidation window (full coverage)'
        def ctx = ApplicationContext.run([
                'redis.caches.reval-unit-cache.expire-after-write': '300s',
                'redis.caches.reval-unit-cache.early-revalidation-window': '300s',
        ], 'test')
        def cache = ctx.getBean(RedisCache, Qualifiers.byName("reval-unit-cache"))

        expect:
        cache.shouldRevalidate(REMAINING_TTL_MS) == EXPECTED

        cleanup:
        ctx.stop()

        where:
        REMAINING_TTL_MS | EXPECTED | SCENARIO
        0                | true     | 'TTL=0, always revalidate'
        -1               | false    | 'no expiry on key (pttl=-1)'
        -2               | false    | 'key missing (pttl=-2)'
        400_000          | false    | 'outside window'
    }

    def 'shouldRevalidate probability increases as expiry approaches: #SCENARIO'() {
        given: 'a cache with 5min TTL and 5min revalidation window (λ = 1/300)'
        def ctx = ApplicationContext.run([
                'redis.caches.reval-prob-cache.expire-after-write': '300s',
                'redis.caches.reval-prob-cache.early-revalidation-window': '300s',
        ], 'test')
        def cache = ctx.getBean(RedisCache, Qualifiers.byName("reval-prob-cache"))

        when: 'run many trials to measure empirical probability'
        int hits = 0
        int trials = 5000
        for (int i = 0; i < trials; i++) {
            if (cache.shouldRevalidate(REMAINING_TTL_MS)) hits++
        }
        double observed = hits / (double) trials

        then: 'observed rate should be within tolerance of expected p = e^(-λ * remainingSeconds)'
        // Blog formula: p(t) = e^(-λ * remainingSeconds), λ = 1/windowSeconds
        observed >= EXPECTED_MIN
        observed <= EXPECTED_MAX

        cleanup:
        ctx.stop()

        where:
        // λ = 1/300, p = e^(-(1/300) * remainingSec)
        REMAINING_TTL_MS | EXPECTED_MIN | EXPECTED_MAX | SCENARIO
        1_000            | 0.98         | 1.00         | '1s left, p≈0.997'
        60_000           | 0.75         | 0.89         | '60s left, p≈0.82'
        150_000          | 0.54         | 0.68         | '150s left (half window), p≈0.61'
        270_000          | 0.34         | 0.48         | '270s left, p≈0.41'
    }

    def 'should trigger early revalidation for cache with revalidation window'() {
        given: 'a cache configured with TTL and early revalidation window'
        def ctx = ApplicationContext.run([
                'redis.caches.revalidate-cache.expire-after-write': '10s',
                'redis.caches.revalidate-cache.early-revalidation-window': '9s',
        ], 'test')
        def cache = ctx.getBean(RedisCache, Qualifiers.byName("revalidate-cache"))
        def supplierCallCount = new AtomicInteger(0)
        def latch = new CountDownLatch(1)

        and: 'put a value in the cache'
        cache.put("revalidate-key", new TestObject(name: "original"))

        when: 'wait until within the revalidation window with low remaining TTL'
        sleep(8000) // 8s elapsed of 10s TTL → ~2s remaining, p = e^(-0.11*2) ≈ 0.80

        and: 'make multiple get calls — high probability triggers revalidation'
        def result = null
        for (int i = 0; i < 50; i++) {
            result = cache.get("revalidate-key", Argument.of(TestObject), {
                supplierCallCount.incrementAndGet()
                latch.countDown()
                return new TestObject(name: "refreshed")
            })
            if (result.name == "refreshed") break
        }

        then: 'supplier should have been called (either via async revalidation or sync on miss)'
        latch.await(5, TimeUnit.SECONDS)
        supplierCallCount.get() >= 1

        and: 'cache should eventually have the refreshed value'
        sleep(500)
        cache.get("revalidate-key", TestObject).get().name == "refreshed"

        cleanup:
        ctx.stop()
    }

    def 'should not trigger early revalidation when TTL is outside window'() {
        given: 'a cache with 30s TTL and 2s revalidation window'
        def ctx = ApplicationContext.run([
                'redis.caches.no-revalidate-cache.expire-after-write': '30s',
                'redis.caches.no-revalidate-cache.early-revalidation-window': '2s',
        ], 'test')
        def cache = ctx.getBean(RedisCache, Qualifiers.byName("no-revalidate-cache"))
        def supplierCallCount = new AtomicInteger(0)

        and: 'put a value — TTL is ~30s, well outside 2s window'
        cache.put("fresh-key", new TestObject(name: "original"))

        when: 'get immediately (remaining TTL ~30s, outside 2s window)'
        for (int i = 0; i < 20; i++) {
            cache.get("fresh-key", Argument.of(TestObject), {
                supplierCallCount.incrementAndGet()
                return new TestObject(name: "refreshed")
            })
        }
        sleep(200) // give any async task time to run

        then: 'supplier should never be called'
        supplierCallCount.get() == 0

        cleanup:
        ctx.stop()
    }

    static class TestObject implements Serializable {
        String name
    }
}
