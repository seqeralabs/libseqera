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

/**
 * Interface for storing and retrieving JWT token pairs by key.
 *
 * <p>This interface allows for different storage strategies for managing
 * multiple user sessions, each with their own token-refresh pair. The key
 * is typically derived from the token itself (e.g., SHA-256 hash).
 *
 * <p>Implementations should be thread-safe as they may be accessed
 * concurrently by multiple requests.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface HxTokenStore {

    /**
     * Retrieves the authentication data for the given key.
     *
     * @param key the unique key identifying the token pair (typically SHA-256 of the token)
     * @return the {@link HxAuth} containing the token pair, or null if not found
     */
    HxAuth get(String key);

    /**
     * Stores the authentication data for the given key.
     *
     * @param key the unique key identifying the token pair (typically SHA-256 of the token)
     * @param auth the {@link HxAuth} containing the token pair to store
     */
    void put(String key, HxAuth auth);

    /**
     * Stores the authentication data for the given key if no value is already associated with it.
     *
     * <p>This method provides atomic check-and-set semantics. If the key already has a value,
     * the existing value is returned and no update is performed. If the key has no value,
     * the provided auth is stored and returned.
     *
     * <p>Implementations should ensure this operation is atomic to prevent race conditions
     * in concurrent environments.
     *
     * @param key the unique key identifying the token pair
     * @param auth the {@link HxAuth} to store if no value exists for the key
     * @return the existing {@link HxAuth} if present, otherwise the newly stored auth
     */
    HxAuth putIfAbsent(String key, HxAuth auth);

    /**
     * Removes the authentication data for the given key.
     *
     * @param key the unique key identifying the token pair to remove
     * @return the removed {@link HxAuth}, or null if not found
     */
    HxAuth remove(String key);
}
