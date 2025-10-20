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
 * Defines the contract for keys used by {@link TieredCache} implementations.
 *
 * <p>This interface allows complex objects to be used as cache keys by providing
 * a stable hash representation. The stable hash is used as the actual key in the
 * underlying cache storage layers.</p>
 *
 * <p>Implementations must ensure that:</p>
 * <ul>
 *   <li>The stable hash is deterministic - same object always produces same hash</li>
 *   <li>The stable hash is unique - different logical objects produce different hashes</li>
 *   <li>The hash string is suitable for use as a cache key (no special characters that might cause issues)</li>
 * </ul>
 *
 * <p>Example implementation:</p>
 * <pre>{@code
 * public class UserCacheKey implements TieredKey {
 *     private final String userId;
 *     private final String tenantId;
 *
 *     @Override
 *     public String stableHash() {
 *         return tenantId + ":" + userId;
 *     }
 * }
 * }</pre>
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
public interface TieredKey {

    /**
     * Returns a stable hash representation of this key.
     *
     * <p>The returned string should be deterministic and unique for each
     * logically distinct key. This string will be used as the actual key
     * in the cache storage.</p>
     *
     * @return a stable string representation suitable for use as a cache key
     */
    String stableHash();
}
