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
 * Interface for JWT authentication credentials.
 *
 * <p>Provides access to the JWT access token, optional refresh token, and optional
 * refresh URL. The {@link #id()} method returns a stable stable identifier for the token
 * store that persists across token refreshes.
 *
 * <p>The {@link #withToken(String)} and {@link #withRefresh(String)} methods return
 * an {@link HxAuth} with the updated token after a refresh operation.
 *
 * <p>A default implementation is provided by {@link DefaultHxAuth}.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface HxAuth {

    /**
     * Returns the JWT access token.
     *
     * @return the access token, never null
     */
    String accessToken();

    /**
     * Returns the refresh token.
     *
     * @return the refresh token, or null if not set
     */
    String refreshToken();

    /**
     * Returns the refresh URL for this auth context.
     *
     * @return the refresh URL, or null to use the global config default
     */
    String refreshUrl();

    /**
     * Returns a stable identifier for this auth in the token store.
     *
     * <p>The key must remain constant across token refreshes so the same
     * auth session can be tracked after {@link #withToken(String)} or
     * {@link #withRefresh(String)} updates.
     *
     * @return the stable identifier
     */
    String id();

    /**
     * Returns an {@link HxAuth} with the given access token.
     *
     * @param token the new access token
     * @return an {@link HxAuth} with the updated token
     */
    HxAuth withToken(String token);

    /**
     * Returns an {@link HxAuth} with the given refresh token.
     *
     * @param refresh the new refresh token
     * @return an {@link HxAuth} with the updated refresh token
     */
    HxAuth withRefresh(String refresh);

}
