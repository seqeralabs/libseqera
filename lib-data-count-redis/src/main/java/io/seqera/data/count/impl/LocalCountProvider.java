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
}
