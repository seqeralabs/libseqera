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

package io.seqera.cache.tiered;

import java.time.Duration;

/**
 * Base interface for tiered-cache system.
 *
 * <p>A tiered cache provides a multi-level caching strategy where values are stored
 * in multiple cache layers (tiers). Typically, this includes a fast local cache (L1)
 * and a distributed cache layer (L2) for scalability across multiple instances.</p>
 *
 * <p>The interface supports:</p>
 * <ul>
 *   <li>Fast retrieval from local cache when available</li>
 *   <li>Automatic fallback to distributed cache on local miss</li>
 *   <li>Time-to-live (TTL) based expiration</li>
 *   <li>Thread-safe operations</li>
 * </ul>
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface TieredCache<K, V> {

    /**
     * Retrieves the value associated with the specified key.
     *
     * <p>This method first checks the local cache (L1), and if not found,
     * checks the distributed cache (L2). If found in L2, the value is
     * automatically cached in L1 for future access.</p>
     *
     * @param key the key whose associated value is to be returned
     * @return the value associated with the specified key, or {@code null} if not found
     */
    V get(K key);

    /**
     * Adds a value to the cache with the specified key and time-to-live.
     *
     * <p>If a value already exists for the given key, it is overridden with
     * the new value. The value will be stored in both L1 (local) and L2
     * (distributed) cache tiers.</p>
     *
     * <p>After the TTL expires, the value will be automatically evicted from
     * the cache and subsequent get() calls will return {@code null}.</p>
     *
     * @param key the key with which the specified value is to be associated
     * @param value the value to be associated with the specified key
     * @param ttl the time-to-live duration after which the value expires
     * @throws IllegalArgumentException if key or value is {@code null}
     */
    void put(K key, V value, Duration ttl);
}
