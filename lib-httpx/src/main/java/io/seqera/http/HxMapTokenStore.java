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

package io.seqera.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default in-memory implementation of {@link HxTokenStore} using a {@link ConcurrentHashMap}.
 *
 * <p>This implementation is thread-safe and suitable for single-instance deployments.
 * For distributed deployments, consider using a custom implementation backed by
 * Redis, a database, or another distributed cache.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxMapTokenStore implements HxTokenStore {

    private final ConcurrentMap<String, HxAuth> store = new ConcurrentHashMap<>();

    @Override
    public HxAuth get(String key) {
        return store.get(key);
    }

    @Override
    public void put(String key, HxAuth auth) {
        store.put(key, auth);
    }

    @Override
    public HxAuth putIfAbsent(String key, HxAuth auth) {
        final HxAuth existing = store.putIfAbsent(key, auth);
        return existing != null ? existing : auth;
    }

    @Override
    public HxAuth remove(String key) {
        return store.remove(key);
    }
}
