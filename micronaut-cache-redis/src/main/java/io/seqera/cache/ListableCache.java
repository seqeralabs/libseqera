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
package io.seqera.cache;

import java.util.Optional;
import java.util.Set;

import io.micronaut.cache.SyncCache;

/**
 * A cache that can list the keys it holds — the one capability Micronaut's {@link SyncCache} lacks.
 *
 * <p>Code that needs to enumerate a cache should not depend on the backend: obtain the listing
 * with {@link #of(SyncCache)}, which covers the Redis cache of this module and Micronaut's own
 * Caffeine cache, so the same code runs with or without Redis.
 *
 * <p>A listing is a snapshot, weakly consistent with concurrent writes: a key may be written,
 * removed or expire right after it is listed, so a caller must still read each key and handle
 * its absence.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface ListableCache {

    /**
     * The keys currently held by the cache, in their string form, materialised as one set — intended
     * for small caches (a registry swept periodically), not for enumerating large ones.
     *
     * <p>A listed key reads its entry back with {@code get(key, ...)} when the cache's keys are
     * strings. The Redis cache always stores the string form of a key, so it round-trips for any key
     * type; Micronaut's Caffeine cache keeps the caller's key object, so a non-string key is listed as
     * its {@code toString()}, which does not read the entry back, and distinct keys with the same
     * {@code toString()} are listed once.
     *
     * @return the keys currently held by the cache, in their string form
     */
    Set<String> keys();

    /**
     * The listing capability of {@code cache}, when its backend supports one: the cache itself when
     * it implements {@link ListableCache} (the Redis cache of this module), a view over the keys of
     * the native Caffeine cache for Micronaut's Caffeine cache, and empty for anything else.
     *
     * @param cache a Micronaut cache
     * @return the cache listing, or empty when the cache cannot be enumerated
     */
    static Optional<ListableCache> of(SyncCache<?> cache) {
        if (cache instanceof ListableCache listable) {
            return Optional.of(listable);
        }
        return CaffeineKeys.of(cache.getNativeCache());
    }
}
