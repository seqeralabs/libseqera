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

package io.seqera.util.http;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for HTTP requests and responses debugging and manipulation.
 *
 * <p>This class provides helper methods for dumping HTTP headers in a readable format,
 * useful for debugging authentication, content negotiation, and other HTTP-related issues.
 * It supports both Micronaut and standard Java HTTP clients.</p>
 *
 * <p>The utility handles different HTTP client types:</p>
 * <ul>
 * <li>Micronaut HttpRequest/HttpResponse objects</li>
 * <li>Standard Java java.net.http.HttpRequest/HttpResponse objects</li>
 * <li>Generic Map-based header structures</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>
 * // Debug Micronaut request headers
 * log.debug("Request headers: {}", HttpUtil.dumpHeaders(request));
 *
 * // Debug standard HTTP response headers
 * log.debug("Response headers: {}", HttpUtil.dumpHeaders(response));
 * </pre>
 *
 * @author Paolo Di Tommaso
 * @since 1.0
 */
public class HttpUtil {

    /**
     * Dumps headers from a Micronaut HttpRequest.
     *
     * @param request The Micronaut HTTP request
     * @return Formatted string containing all headers, or null if no headers
     */
    public static String dumpHeaders(io.micronaut.http.HttpRequest<?> request) {
        return dumpHeaders(request.getHeaders().asMap());
    }

    /**
     * Dumps headers from a Micronaut HttpResponse.
     *
     * @param response The Micronaut HTTP response
     * @return Formatted string containing all headers, or null if no headers
     */
    public static String dumpHeaders(io.micronaut.http.HttpResponse<?> response) {
        return dumpHeaders(response.getHeaders().asMap());
    }

    /**
     * Dumps headers from a standard Java HttpRequest.
     *
     * @param request The standard Java HTTP request
     * @return Formatted string containing all headers, or null if no headers
     */
    public static String dumpHeaders(HttpRequest request) {
        return dumpHeaders(request.headers().map());
    }

    /**
     * Dumps headers from a standard Java HttpResponse.
     *
     * @param response The standard Java HTTP response
     * @return Formatted string containing all headers, or null if no headers
     */
    public static String dumpHeaders(HttpResponse<?> response) {
        return dumpHeaders(response.headers().map());
    }

    /**
     * Dumps headers from a generic Map structure.
     *
     * <p>Each header name can have multiple values (as per HTTP specification).
     * The output format is one line per header value:
     * <pre>
     *   Content-Type=application/json
     *   Authorization=Bearer token123
     *   Accept=application/json
     * </pre></p>
     *
     * @param headers Map of header names to lists of values
     * @return Formatted string containing all headers, or null if no headers
     */
    public static String dumpHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String val : entry.getValue()) {
                result.append("\n  ").append(entry.getKey()).append("=").append(val);
            }
        }
        return result.toString();
    }

    /**
     * Dumps parameters from a Micronaut HttpRequest.
     *
     * @param request The Micronaut HTTP request
     * @return Formatted string containing all parameters, or null if no parameters
     */
    public static String dumpParams(io.micronaut.http.HttpRequest<?> request) {
        return dumpParams(request.getParameters().asMap());
    }

    /**
     * Dumps parameters from a generic Map structure.
     *
     * <p>Each parameter name can have multiple values (as per HTTP specification).
     * The output format is one line per parameter value:
     * <pre>
     *   code=auth_code_123
     *   state=secure_state_456
     *   redirect_uri=http://localhost:8080/callback
     * </pre></p>
     *
     * @param params Map of parameter names to lists of values
     * @return Formatted string containing all parameters, or null if no parameters
     */
    public static String dumpParams(Map<String, List<String>> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            for (String val : entry.getValue()) {
                result.append("\n  ").append(entry.getKey()).append("=").append(val);
            }
        }
        return result.toString();
    }

    /**
     * Masks sensitive data in form parameters or any Map structure.
     *
     * <p>This method creates a new Map with sensitive values masked to prevent
     * them from appearing in full in debug logs. Sensitive fields are truncated
     * to show only the first 10 characters followed by "...".</p>
     *
     * <p>Default sensitive fields that are masked:</p>
     * <ul>
     * <li>client_secret - OAuth client secret</li>
     * <li>code - OAuth authorization code</li>
     * <li>refresh_token - OAuth refresh token</li>
     * <li>access_token - OAuth access token</li>
     * <li>password - User password</li>
     * </ul>
     *
     * @param params Map containing potentially sensitive data
     * @param sensitiveKeys List of keys to mask (optional, uses defaults if null)
     * @return New Map with sensitive values masked
     */
    public static Map<String, Object> maskParams(Map<String, Object> params, List<String> sensitiveKeys) {
        if (params == null) {
            return params;
        }

        List<String> defaultSensitiveKeys = Arrays.asList(
            "client_secret", "code", "refresh_token", "access_token", "password"
        );
        List<String> keysToMask = (sensitiveKeys != null) ? sensitiveKeys : defaultSensitiveKeys;

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (keysToMask.contains(key)) {
                String stringValue = (value != null) ? value.toString() : "";
                String masked = stringValue.substring(0, Math.min(10, stringValue.length())) + "...";
                result.put(key, masked);
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Masks sensitive data in form parameters with default sensitive keys.
     *
     * @param params Map containing potentially sensitive data
     * @return New Map with sensitive values masked
     */
    public static Map<String, Object> maskParams(Map<String, Object> params) {
        return maskParams(params, null);
    }
}
