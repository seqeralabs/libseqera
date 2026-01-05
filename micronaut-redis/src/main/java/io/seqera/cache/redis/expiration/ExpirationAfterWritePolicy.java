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
package io.seqera.cache.redis.expiration;

/**
 * Interface that defines a TTL policy for a Redis cache.
 * Implement this interface to provide custom expiration logic based on the cached value.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface ExpirationAfterWritePolicy {

    /**
     * Calculate the TTL for a value being put into the cache.
     *
     * @param value Object that will be put in the cache (non-serialized)
     * @return TTL of the entry in Redis in milliseconds
     */
    long getExpirationAfterWrite(Object value);
}
