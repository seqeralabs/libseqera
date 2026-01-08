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
 * Provides a cache TTL policy with a constant expiration time.
 * All cached values will have the same TTL regardless of their content.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ConstantExpirationAfterWritePolicy implements ExpirationAfterWritePolicy {

    private final long ttl;

    /**
     * Constructor.
     *
     * @param ttl TTL in milliseconds
     */
    public ConstantExpirationAfterWritePolicy(long ttl) {
        this.ttl = ttl;
    }

    @Override
    public long getExpirationAfterWrite(Object value) {
        return ttl;
    }
}
