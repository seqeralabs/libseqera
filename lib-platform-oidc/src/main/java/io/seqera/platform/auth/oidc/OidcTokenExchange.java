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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Exchanges an OAuth2 authorization code for an access token at the OIDC token endpoint.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class OidcTokenExchange {

    private final HttpClient httpClient;

    public OidcTokenExchange() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public OidcTokenExchange(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Exchange an authorization code for an access token.
     *
     * @param tokenEndpoint the OIDC token endpoint URL
     * @param clientId the OAuth2 client ID
     * @param code the authorization code from the callback
     * @param codeVerifier the PKCE code verifier
     * @param redirectUri the redirect URI used in the authorization request
     * @return the access token
     * @throws IOException if the token exchange fails
     */
    public String exchange(String tokenEndpoint, String clientId, String code,
                           String codeVerifier, String redirectUri) throws IOException {
        String body = "grant_type=authorization_code"
                + "&client_id=" + urlEncode(clientId)
                + "&code=" + urlEncode(code)
                + "&code_verifier=" + urlEncode(codeVerifier)
                + "&redirect_uri=" + urlEncode(redirectUri);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Token exchange failed (HTTP " + response.statusCode() + "): " + response.body());
            }
            return extractAccessToken(response.body());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Token exchange interrupted", e);
        }
    }

    static String extractAccessToken(String json) {
        String token = OidcDiscovery.extractJsonString(json, "access_token");
        if (token == null) {
            throw new IllegalArgumentException("Token response missing access_token");
        }
        return token;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
