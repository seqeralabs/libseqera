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


import java.util.concurrent.CompletableFuture

/**
 * Implements a {@link FutureStore} that allow handling {@link CompletableFuture} objects
 * in a distributed environment.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface FutureStore<K,V> {

    /**
     * Create a {@link CompletableFuture} object
     *
     * @param key The unique id associated with the future object
     * @return A {@link CompletableFuture} object holding the future result
     */
    CompletableFuture<V> create(K key)

    /**
     * Complete the {@link CompletableFuture} object with the specified key
     *
     * @param key The unique key of the future to complete
     * @param value The value to used to complete the future
     */
    void complete(K key, V value)

}
