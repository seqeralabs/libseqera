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

/**
 * Enumeration of supported HTTP authentication schemes as defined in RFC 7235.
 * 
 * <p>This enum represents the authentication schemes that HxClient can handle
 * when processing WWW-Authenticate header challenges from HTTP 401 responses.
 * 
 * <p>Currently supported schemes:
 * <ul>
 *   <li><strong>Basic</strong>: RFC 7617 Basic HTTP Authentication using username:password credentials</li>
 *   <li><strong>Bearer</strong>: RFC 6750 Bearer Token Authentication using JWT or other token formats</li>
 * </ul>
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see AuthenticationChallenge
 * @see WwwAuthenticateParser
 */
public enum AuthenticationScheme {
    
    /**
     * Basic HTTP Authentication scheme as defined in RFC 7617.
     * Uses base64-encoded "username:password" credentials in the Authorization header.
     */
    BASIC("Basic"),
    
    /**
     * Bearer Token Authentication scheme as defined in RFC 6750.
     * Uses a token (typically JWT) in the Authorization header with "Bearer " prefix.
     */
    BEARER("Bearer");
    
    private final String schemeName;
    
    AuthenticationScheme(String schemeName) {
        this.schemeName = schemeName;
    }
    
    /**
     * Returns the string representation of the authentication scheme as it appears in HTTP headers.
     * 
     * @return the scheme name (e.g., "Basic", "Bearer")
     */
    public String getSchemeName() {
        return schemeName;
    }
    
    /**
     * Returns the AuthenticationScheme for the given scheme name, ignoring case.
     * 
     * <p>This method performs case-insensitive matching of scheme names as defined
     * in RFC 7235. Leading and trailing whitespace is automatically trimmed.
     * 
     * <p><strong>Supported Scheme Names:</strong>
     * <ul>
     *   <li>"Basic", "BASIC", "basic" → {@link #BASIC}</li>
     *   <li>"Bearer", "BEARER", "bearer" → {@link #BEARER}</li>
     * </ul>
     * 
     * <p><strong>Usage Examples:</strong>
     * <pre>{@code
     * AuthenticationScheme.fromString("Basic")   // Returns BASIC
     * AuthenticationScheme.fromString("bearer")  // Returns BEARER  
     * AuthenticationScheme.fromString(" Basic ") // Returns BASIC (whitespace trimmed)
     * AuthenticationScheme.fromString("Digest")  // Returns null (unsupported)
     * AuthenticationScheme.fromString(null)      // Returns null
     * AuthenticationScheme.fromString("")        // Returns null
     * }</pre>
     * 
     * @param schemeName the scheme name to parse (e.g., "basic", "BEARER").
     *                   Null, empty strings, and whitespace-only strings are handled gracefully.
     * @return the matching AuthenticationScheme, or null if the scheme is not supported,
     *         null, or empty after trimming
     */
    public static AuthenticationScheme fromString(String schemeName) {
        if (schemeName == null || schemeName.isEmpty()) {
            return null;
        }
        
        for (AuthenticationScheme scheme : values()) {
            if (scheme.schemeName.equalsIgnoreCase(schemeName.trim())) {
                return scheme;
            }
        }
        
        return null;
    }
    
    @Override
    public String toString() {
        return schemeName;
    }
}
