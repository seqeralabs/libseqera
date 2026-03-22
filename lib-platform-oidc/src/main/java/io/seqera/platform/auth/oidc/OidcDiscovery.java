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
 */

package io.seqera.platform.auth.oidc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fetches OIDC provider configuration from the well-known discovery endpoint.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class OidcDiscovery {

    private final HttpClient httpClient;

    public OidcDiscovery() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public OidcDiscovery(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Fetch the OIDC configuration from the given Platform API endpoint.
     *
     * @param endpoint the Platform API base URL (e.g. {@code https://api.cloud.seqera.io})
     * @return the OIDC configuration with authorization and token endpoints
     * @throws IOException if the discovery request fails
     */
    public OidcConfig discover(String endpoint) throws IOException {
        String url = endpoint.endsWith("/")
                ? endpoint + ".well-known/openid-configuration"
                : endpoint + "/.well-known/openid-configuration";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("OIDC discovery failed: HTTP " + response.statusCode());
            }
            return parseConfig(response.body());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OIDC discovery interrupted", e);
        }
    }

    static OidcConfig parseConfig(String json) {
        String authEndpoint = extractJsonString(json, "authorization_endpoint");
        String tokenEndpoint = extractJsonString(json, "token_endpoint");
        if (authEndpoint == null || tokenEndpoint == null) {
            throw new IllegalArgumentException(
                    "OIDC discovery response missing required endpoints");
        }
        return new OidcConfig(authEndpoint, tokenEndpoint);
    }

    /**
     * Minimal JSON string-value extractor for flat JSON objects with unescaped string values.
     *
     * <p>This handles the well-structured JSON returned by OIDC discovery and token endpoints.
     * It does NOT support escaped quotes in values, nested objects, or arrays. This is acceptable
     * because OIDC discovery values are URLs and token values are JWTs — neither contain escaped quotes.
     */
    static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        // skip past key's closing quote, the colon, and any whitespace to find value's opening quote
        int start = json.indexOf('"', idx + search.length() + 1) + 1;
        int end = json.indexOf('"', start);
        if (start <= 0 || end < 0) return null;
        return json.substring(start, end);
    }
}
