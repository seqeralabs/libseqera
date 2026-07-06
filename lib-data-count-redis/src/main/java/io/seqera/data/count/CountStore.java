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

package io.seqera.data.count;

/**
 * Define the contract for a distributed counter similar to Redis {@code INCRBY}/{@code DECRBY}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface CountStore {

    long increment(String key);

    long increment(String key, long value);

    long decrement(String key);

    long decrement(String key, long value);

    long get(String key);

    void clear(String key);

    /**
     * Atomically increment the counter by {@code value} only if the resulting total would not
     * exceed {@code limit} — a bounded check-and-increment that never lets concurrent callers
     * collectively cross the limit.
     *
     * <p><b>TTL policy.</b> {@code ttlSeconds} is applied by the distributed (Redis) implementation
     * only when the key is first created and is not refreshed thereafter (a safety-net expiry for an
     * abandoned counter); the in-memory implementation ignores it. A value {@code <= 0} sets no
     * expiry. See {@code CountProvider#tryAcquire} for the full contract.
     *
     * @param key         the counter key
     * @param value       the amount to add; must be {@code >= 0}
     * @param limit       the inclusive maximum the counter may reach; must be {@code >= 0}
     * @param ttlSeconds  first-create expiry for the distributed impl; {@code <= 0} means no expiry;
     *                    ignored by the in-memory impl
     * @return {@code true} if applied (admitted); {@code false} if it would exceed {@code limit}
     *         (rejected, counter unchanged)
     * @throws IllegalArgumentException if {@code value} or {@code limit} is negative
     */
    boolean tryAcquire(String key, long value, long limit, long ttlSeconds);
}
