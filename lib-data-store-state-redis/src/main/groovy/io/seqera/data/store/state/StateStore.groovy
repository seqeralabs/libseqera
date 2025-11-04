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

package io.seqera.data.store.state

import java.time.Duration

/**
 * Interface for cache store operations
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface StateStore<K, V> {

    /**
     * Retrieve a cached object by the given key
     *
     * @param key
     *      The key of the object to be retrieved
     * @return
     *      The object matching the specified key, or {@code null} if no object exists
     */
    V get(K key)

    /**
     * Store a the specified key-value pair in the underlying cache
     *
     * @param key The key to retrieve the associated value
     * @param value The value to be store in the cache
     */
    void put(K key, V value)

    /**
     * Store a the specified key-value pair in the underlying cache
     *
     * @param key The key to retrieve the associated value
     * @param value The value to be store in the cache
     * @param ttl The max time-to-live of the stored entry
     */
    void put(K key, V value, Duration ttl)

    /**
     * Store a value in the cache only if does not exist yet
     * @param key The unique associated with this object
     * @param value The object to store
     * @return {@code true} if the value was stored, {@code false} otherwise
     */
    boolean putIfAbsent(K key, V value)

    /**
     * Store a value in the cache only if does not exist
     *
     * @param key The unique associated with this object
     * @param value The object to store
     * @param ttl The max time-to-live of the stored entry
     * @return {@code true} if the value was stored, {@code false} otherwise
     */
    boolean putIfAbsent(K key, V value, Duration ttl)

    /**
     * Remove the entry with the specified key from the cache
     *
     * @param key The key of the entry to be removed
     */
    void remove(K key)

    /**
     * Remove all entries from the cache
     */
    void clear()

}
