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

/**
 * Capability contract for providers supporting a versioned compare-and-swap write —
 * the storage primitive behind {@code VersionedStateStore}. Deliberately independent
 * of {@link StateProvider}: a provider offers versioned writes by implementing both.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface VersionProvider<K,V> {

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
    boolean replaceIf(K key, long expected, V value)

    /**
     * Same as {@link #replaceIf(Object, long, Object)}, resetting the entry
     * time-to-live to the specified duration.
     *
     * @param key The key of the entry to be replaced
     * @param expected The version the stored form is expected to carry
     * @param value The new value to be stored; must not be {@code null}
     * @param ttl The new max time-to-live of the entry once replaced
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *      exist or the stored version differs from the expected one
     */
    boolean replaceIf(K key, long expected, V value, Duration ttl)

}
