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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an HTTP authentication challenge extracted from a WWW-Authenticate header.
 * 
 * <p>An authentication challenge consists of an authentication scheme (e.g., Basic, Bearer)
 * and optional parameters such as realm. This class provides a simple representation
 * of challenges as defined in RFC 7235.
 * 
 * <p>Example WWW-Authenticate challenges:
 * <pre>
 * Basic realm="Protected Area"
 * Bearer realm="api", scope="read write"
 * </pre>
 * 
 * <p>This class is immutable and thread-safe.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see AuthenticationScheme
 * @see WwwAuthenticateParser
 */
public class AuthenticationChallenge {
    
    private final AuthenticationScheme scheme;
    private final Map<String, String> parameters;
    
    /**
     * Creates a new authentication challenge with the specified scheme and parameters.
     * 
     * @param scheme the authentication scheme (Basic, Bearer, etc.)
     * @param parameters the challenge parameters (realm, scope, etc.). May be null or empty.
     */
    public AuthenticationChallenge(AuthenticationScheme scheme, Map<String, String> parameters) {
        this.scheme = Objects.requireNonNull(scheme, "Authentication scheme cannot be null");
        this.parameters = parameters != null ? Map.copyOf(parameters) : Collections.emptyMap();
    }
    
    /**
     * Creates a new authentication challenge with the specified scheme and no parameters.
     * 
     * @param scheme the authentication scheme (Basic, Bearer, etc.)
     */
    public AuthenticationChallenge(AuthenticationScheme scheme) {
        this(scheme, null);
    }
    
    /**
     * Returns the authentication scheme for this challenge.
     * 
     * @return the authentication scheme
     */
    public AuthenticationScheme getScheme() {
        return scheme;
    }
    
    /**
     * Returns the challenge parameters as an immutable map.
     * 
     * <p>Common parameters include:
     * <ul>
     *   <li><strong>realm</strong>: A string indicating the protection space</li>
     *   <li><strong>scope</strong>: The scope of access being requested (Bearer)</li>
     * </ul>
     * 
     * @return an immutable map of challenge parameters
     */
    public Map<String, String> getParameters() {
        return parameters;
    }
    
    /**
     * Returns the value of a specific challenge parameter.
     * 
     * @param name the parameter name (case-sensitive)
     * @return the parameter value, or null if not present
     */
    public String getParameter(String name) {
        return parameters.get(name);
    }
    
    /**
     * Returns the realm parameter value, which indicates the protection space.
     * 
     * @return the realm value, or null if not specified
     */
    public String getRealm() {
        return getParameter("realm");
    }
    
    /**
     * Checks if this challenge has any parameters.
     * 
     * @return true if the challenge has parameters, false otherwise
     */
    public boolean hasParameters() {
        return !parameters.isEmpty();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuthenticationChallenge that = (AuthenticationChallenge) o;
        return scheme == that.scheme && Objects.equals(parameters, that.parameters);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(scheme, parameters);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme.getSchemeName());
        
        if (hasParameters()) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                sb.append(" ").append(entry.getKey()).append("=");
                String value = entry.getValue();
                // Quote values that contain spaces or special characters
                if (value.contains(" ") || value.contains(",") || value.contains("\"")) {
                    sb.append("\"").append(value.replace("\"", "\\\"")).append("\"");
                } else {
                    sb.append(value);
                }
            }
        }
        
        return sb.toString();
    }
}
