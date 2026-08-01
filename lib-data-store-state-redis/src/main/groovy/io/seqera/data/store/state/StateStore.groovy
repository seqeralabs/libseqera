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
     * Replace the value associated with the specified key only if the current value
     * equals the expected one (compare-and-swap). The comparison is performed on the
     * stored (serialized) form of the value.
     *
     * <p>The entry retains its remaining time-to-live.
     *
     * @param key The key of the entry to be replaced
     * @param expected The value the entry is expected to currently hold; must not be {@code null}
     * @param value The new value to be stored; must not be {@code null}
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the current value differs from the expected one
     */
    boolean replaceIf(K key, V expected, V value)

    /**
     * Replace the value associated with the specified key only if the current value
     * equals the expected one (compare-and-swap), resetting the entry time-to-live
     * to the specified duration.
     *
     * @param key The key of the entry to be replaced
     * @param expected The value the entry is expected to currently hold; must not be {@code null}
     * @param value The new value to be stored; must not be {@code null}
     * @param ttl The new max time-to-live of the entry once replaced
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the current value differs from the expected one
     */
    boolean replaceIf(K key, V expected, V value, Duration ttl)

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
