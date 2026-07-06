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

package io.seqera.data.count.impl;

/**
 * Contract for count store provider
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface CountProvider {

    long increment(String key, long value);

    long decrement(String key, long value);

    long get(String key);

    void clear(String key);

    /**
     * Atomically increment the counter by {@code value} only if the resulting total would not
     * exceed {@code limit} — a bounded check-and-increment. The read, the ceiling check and the
     * increment happen as a single indivisible operation, so concurrent callers (including across
     * processes, for the distributed implementation) can never collectively push the counter past
     * the limit.
     *
     * <p><b>TTL policy.</b> {@code ttlSeconds} is a safety-net expiry applied by the distributed
     * (Redis) implementation <b>only when the key is first created</b> (a fresh counter starting
     * from this increment); it is <b>not refreshed</b> on subsequent acquires, so a key hard-expires
     * at creation-time + ttl regardless of later activity. This intentionally bounds the lifetime of
     * an abandoned counter (e.g. a missed {@link #decrement}). A non-positive {@code ttlSeconds} sets
     * no expiry. The in-memory implementation <b>ignores {@code ttlSeconds}</b> — its entries live
     * for the process lifetime — so callers must not rely on TTL-based reset outside a distributed
     * deployment.
     *
     * @param key         the counter key
     * @param value       the amount to add; must be {@code >= 0}
     * @param limit       the inclusive maximum the counter may reach; must be {@code >= 0}
     * @param ttlSeconds  first-create expiry for the distributed impl; {@code <= 0} means no expiry;
     *                    ignored by the in-memory impl
     * @return {@code true} if the increment was applied (admitted); {@code false} if it would have
     *         exceeded {@code limit} (rejected, counter unchanged)
     * @throws IllegalArgumentException if {@code value} or {@code limit} is negative
     */
    boolean tryAcquire(String key, long value, long limit, long ttlSeconds);
}
