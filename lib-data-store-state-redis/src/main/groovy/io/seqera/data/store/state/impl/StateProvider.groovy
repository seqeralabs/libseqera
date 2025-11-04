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
