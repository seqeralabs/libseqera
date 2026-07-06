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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local based implementation for a distributed counter
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(missingBeans = RedisActivator.class)
@Singleton
public class LocalCountProvider implements CountProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalCountProvider.class);

    private final ConcurrentHashMap<String, AtomicLong> store = new ConcurrentHashMap<>();

    @Override
    public long increment(String key, long value) {
        long result = store.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(value);
        log.trace("* increment key={} value={} result={}", key, value, result);
        return result;
    }

    @Override
    public long decrement(String key, long value) {
        long result = store.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(-value);
        log.trace("* decrement key={} value={} result={}", key, value, result);
        return result;
    }

    @Override
    public long get(String key) {
        AtomicLong counter = store.get(key);
        return counter != null ? counter.get() : 0;
    }

    @Override
    public void clear(String key) {
        store.remove(key);
        log.trace("* clear key={}", key);
    }

    @Override
    public boolean tryAcquire(String key, long value, long limit, long ttlSeconds) {
        validate(value, limit);
        // Lock-free compare-and-set loop: read the current value, check the ceiling, and only then
        // attempt the increment; retry if another thread changed the value in between. The loop is
        // bounded — it returns on the two outcomes below (over-limit, or a successful CAS) and only
        // retries when the CAS loses a race, which means another thread just made progress (the
        // standard lock-free retry idiom, as used internally by AtomicLong.updateAndGet). The
        // return decision lives outside the CAS attempt, so it is computed exactly once per outcome.
        // The ttlSeconds argument does not apply to the in-memory store (entries live for the
        // process lifetime); it is accepted for parity with the Redis impl.
        final AtomicLong counter = store.computeIfAbsent(key, k -> new AtomicLong());
        while (true) {
            final long current = counter.get();
            if (current + value > limit) {
                log.trace("* tryAcquire key={} value={} limit={} admitted=false", key, value, limit);
                return false;                                  // exit: reject, counter unchanged
            }
            if (counter.compareAndSet(current, current + value)) {
                log.trace("* tryAcquire key={} value={} limit={} admitted=true", key, value, limit);
                return true;                                   // exit: admit, vcpus reserved
            }
            // lost the race — another thread updated the counter; re-read and retry
        }
    }

    private static void validate(long value, long limit) {
        if (value < 0)
            throw new IllegalArgumentException("tryAcquire value must be non-negative, got " + value);
        if (limit < 0)
            throw new IllegalArgumentException("tryAcquire limit must be non-negative, got " + limit);
    }
}
