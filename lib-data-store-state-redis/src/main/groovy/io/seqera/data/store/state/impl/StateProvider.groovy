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

import java.time.Duration

import io.seqera.data.store.state.CountParams
import io.seqera.data.store.state.CountResult
import io.seqera.data.store.state.StateStore
/**
 * Define an cache interface alias to be used by cache implementation providers
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface StateProvider<K,V> extends StateStore<K,V> {

    /**
     * Replace the value associated with the specified key only if the current stored
     * value equals the expected one (compare-and-swap on the stored form). This is the
     * raw atomicity primitive backing {@code AbstractStateStore#replaceIf}; the expected
     * value must be the stored form exactly as previously read, never a re-serialization.
     *
     * <p>The entry retains its remaining time-to-live.
     *
     * @param key The key of the entry to be replaced
     * @param expected The stored form the entry is expected to currently hold; must not be {@code null}
     * @param value The new value to be stored; must not be {@code null}
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the current value differs from the expected one
     */
    boolean replaceIf(K key, V expected, V value)

    /**
     * Same as {@link #replaceIf(Object, Object, Object)}, resetting the entry
     * time-to-live to the specified duration.
     *
     * @param key The key of the entry to be replaced
     * @param expected The stored form the entry is expected to currently hold; must not be {@code null}
     * @param value The new value to be stored; must not be {@code null}
     * @param ttl The new max time-to-live of the entry once replaced
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the current value differs from the expected one
     */
    boolean replaceIf(K key, V expected, V value, Duration ttl)

    /**
     * Replace the value associated with the specified key only if the version carried by
     * the leading {@code {"@v":N} frame of the stored form equals the expected one
     * (versioned compare-and-swap). A stored form without the frame counts as version 0;
     * a missing key never matches. The whole operation is a single atomic server-side
     * call, and only the head of the stored value is inspected, so the cost is
     * independent of the payload size.
     *
     * <p>The entry retains its remaining time-to-live.
     *
     * @param key The key of the entry to be replaced
     * @param expected The version the stored form is expected to carry
     * @param value The new value to be stored; must not be {@code null}
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the stored version differs from the expected one
     */
    boolean replaceIfVersion(K key, long expected, V value)

    /**
     * Same as {@link #replaceIfVersion(Object, long, Object)}, resetting the entry
     * time-to-live to the specified duration.
     *
     * @param key The key of the entry to be replaced
     * @param expected The version the stored form is expected to carry
     * @param value The new value to be stored; must not be {@code null}
     * @param ttl The new max time-to-live of the entry once replaced
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the stored version differs from the expected one
     */
    boolean replaceIfVersion(K key, long expected, V value, Duration ttl)

    /**
     * Store a value in the cache only if does not exist. If the operation is successful
     * the counter identified by the key specified is incremented by 1 and the counter (new)
     * value is returned as result the operation.
     *
     * @param key
     *      The unique associated with this object
     * @param value
     *      A JSON payload to be stored. It attribute "count" is updated with the counter incremented value
     * @param counterKey
     *      The counter unique key to be incremented
     * @param ttl
     *      The max time-to-live of the stored entry
     * @return
     *      A tuple with 3 elements with the following semantic: <result, value, count>, where "result" is {@code true}
     *      when the value was actually updated or {@code false} otherwise. "value" represent the specified value when
     *      "return" is true or the value currently existing if the key already exist. Finally "count" is the value
     *      of the count after the increment operation.
     */
    CountResult<V> putJsonIfAbsentAndIncreaseCount(K key, V value, Duration ttl, CountParams counterKey, String luaScript)

}
