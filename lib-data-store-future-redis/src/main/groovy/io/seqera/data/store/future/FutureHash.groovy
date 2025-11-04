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

package io.seqera.data.store.future

import java.time.Duration
/**
 * Define the interface for a future distributed hash
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface FutureHash<V> {

    /**
     * Add a value in the distributed "queue". The value is evicted after
     * the specified expired duration
     *
     * @param key The key associated with the provided value
     * @param value The value to be stored.
     * @param expiration The amount of time after which the value is evicted
     */
    void put(String key, V value, Duration expiration)

    /**
     * Get the value with the specified key
     *
     * @param key The key of the value to be taken
     * @return The value associated with the specified key or {@code null} otherwise
     */
    V take(String key)

}
