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
package io.seqera.cache;

import java.util.Optional;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;

/**
 * Lists the keys of a native Caffeine cache, the backend of Micronaut's in-process cache.
 *
 * <p>Caffeine is an optional dependency of this module, so its classes are touched only after
 * {@link #AVAILABLE} confirms they are on the classpath; the Caffeine-typed code lives in
 * {@link View}, which the JVM loads only on that path.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
final class CaffeineKeys {

    private static final boolean AVAILABLE = isPresent();

    private CaffeineKeys() {}

    static Optional<ListableCache> of(Object nativeCache) {
        return AVAILABLE ? View.of(nativeCache) : Optional.empty();
    }

    private static boolean isPresent() {
        try {
            Class.forName("com.github.benmanes.caffeine.cache.Cache", false, CaffeineKeys.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static final class View {
        static Optional<ListableCache> of(Object nativeCache) {
            if (!(nativeCache instanceof Cache<?, ?> cache)) {
                return Optional.empty();
            }
            return Optional.of(() -> cache.asMap().keySet().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toUnmodifiableSet()));
        }
    }
}
