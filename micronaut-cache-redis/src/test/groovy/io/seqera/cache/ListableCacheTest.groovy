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
package io.seqera.cache

import io.micronaut.cache.CacheManager
import io.micronaut.cache.SyncCache
import io.micronaut.context.ApplicationContext
import spock.lang.Specification

/**
 * Tests for {@link ListableCache#of} on the non-Redis backends; the Redis case is in {@code RedisCacheTest}.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ListableCacheTest extends Specification {

    def 'should list the keys of a Micronaut Caffeine cache'() {
        given:
        def context = ApplicationContext.run(['micronaut.caches.local-cache.maximum-size': 10], 'test')
        def cache = context.getBean(CacheManager).getCache("local-cache")
        cache.put("one", 1)
        cache.put("two", 2)

        expect:
        ListableCache.of(cache).get().keys() == ["one", "two"] as Set

        cleanup:
        context.close()
    }

    def 'should offer no listing for a cache it cannot enumerate'() {
        given:
        def cache = Stub(SyncCache) { getNativeCache() >> new Object() }

        expect:
        ListableCache.of(cache).isEmpty()
    }
}
