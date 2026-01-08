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

    static class TestObject implements Serializable {
        String name
    }
}
