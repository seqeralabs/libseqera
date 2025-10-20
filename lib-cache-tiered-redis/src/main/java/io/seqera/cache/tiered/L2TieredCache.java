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

/**
 * Defines the interface for the second-level (L2) tiered cache.
 *
 * <p>The L2 cache typically represents a distributed cache layer (e.g., Redis)
 * that provides caching across multiple application instances. Unlike the L1
 * cache which is local to each instance, the L2 cache is shared and enables
 * cache consistency in distributed deployments.</p>
 *
 * <p>Characteristics of L2 cache:</p>
 * <ul>
 *   <li>Distributed across multiple nodes</li>
 *   <li>Slower than L1 but still faster than computing/fetching the value</li>
 *   <li>Persistent across application restarts (depending on implementation)</li>
 *   <li>Provides eventual consistency in multi-instance deployments</li>
 * </ul>
 *
 * <p>This interface extends {@link TieredCache} without adding new methods,
 * serving primarily as a type marker to distinguish L2 cache implementations
 * from general tiered caches.</p>
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface L2TieredCache<K, V> extends TieredCache<K, V> {
}
