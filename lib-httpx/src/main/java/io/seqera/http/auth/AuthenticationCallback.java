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

package io.seqera.http.auth;

import io.seqera.http.HxConfig;

/**
 * Callback interface for providing authentication credentials in response to HTTP challenges.
 * 
 * <p>This interface allows applications to supply authentication credentials when HxClient
 * encounters a 401 Unauthorized response with WWW-Authenticate challenges. The callback
 * is invoked for each supported authentication scheme found in the response.
 * 
 * <p>The callback should return appropriate credentials for the requested scheme and realm:
 * <ul>
 *   <li><strong>Basic Authentication</strong>: Return base64-encoded "username:password" string</li>
 *   <li><strong>Bearer Authentication</strong>: Return the bearer token value (without "Bearer " prefix)</li>
 * </ul>
 * 
 * <p>If credentials are not available for the requested scheme/realm combination,
 * the callback should return null. HxClient will then attempt anonymous authentication
 * if configured to do so.
 * 
 * <p>Usage example:
 * <pre>{@code
 * AuthenticationCallback callback = (scheme, realm) -> {
 *     switch (scheme) {
 *         case BASIC:
 *             if ("Protected Area".equals(realm)) {
 *                 String credentials = "user:password";
 *                 return Base64.getEncoder().encodeToString(credentials.getBytes());
 *             }
 *             break;
 *         case BEARER:
 *             if ("api".equals(realm)) {
 *                 return getApiToken(); // Return JWT or other token
 *             }
 *             break;
 *     }
 *     return null; // No credentials available
 * };
 * 
 * HxConfig config = HxConfig.newBuilder()
 *     .withWwwAuthenticateHandling(true)
 *     .withAuthenticationCallback(callback)
 *     .build();
 * }</pre>
 * 
 * <p>This interface is designed to be implemented as a lambda expression or method reference
 * for simple use cases, while also supporting more complex credential management scenarios.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see AuthenticationChallenge
 * @see AuthenticationScheme
 * @see HxConfig
 */
@FunctionalInterface
public interface AuthenticationCallback {
    
    /**
     * Provides authentication credentials for the specified scheme and realm.
     * 
     * <p>This method is called when HxClient receives a 401 Unauthorized response
     * with a WWW-Authenticate header containing supported authentication challenges.
     * The implementation should examine the scheme and realm to determine if
     * appropriate credentials are available.
     * 
     * <p>Credential format requirements:
     * <ul>
     *   <li><strong>Basic</strong>: Base64-encoded "username:password" string</li>
     *   <li><strong>Bearer</strong>: Token value without "Bearer " prefix</li>
     * </ul>
     * 
     * <p>The method may be called multiple times for different schemes if the server
     * provides multiple authentication options. The first successful authentication
     * will be used for the retry attempt.
     * 
     * @param scheme the authentication scheme being requested (Basic, Bearer, etc.)
     * @param realm the protection realm, or null if not specified in the challenge.
     *              The realm helps identify which credentials to use when multiple
     *              protected areas exist on the same server.
     * @return the credentials for the specified scheme/realm, or null if not available
     */
    String getCredentials(AuthenticationScheme scheme, String realm);
}
