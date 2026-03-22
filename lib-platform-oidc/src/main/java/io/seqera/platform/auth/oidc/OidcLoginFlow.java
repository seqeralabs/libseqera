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

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Orchestrates the full OIDC Authorization Code + PKCE login flow.
 *
 * <p>This class coordinates OIDC discovery, PKCE challenge generation,
 * a local callback server, browser-based authentication, and token exchange
 * to obtain an OAuth2 access token from Seqera Platform.
 *
 * <p>Use {@link #newBuilder()} to create an instance:
 * <pre>{@code
 * String token = OidcLoginFlow.builder()
 *     .endpoint("https://api.cloud.seqera.io")
 *     .clientId("nextflow_cli")
 *     .build()
 *     .login(url -> Desktop.getDesktop().browse(URI.create(url)));
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class OidcLoginFlow {

    private static final String DEFAULT_SCOPES = "openid profile email offline_access";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;
    private final String clientId;
    private final String scopes;
    private final Duration timeout;
    private final OidcDiscovery discovery;
    private final OidcTokenExchange tokenExchange;

    private OidcLoginFlow(Builder builder) {
        this.endpoint = builder.endpoint;
        this.clientId = builder.clientId;
        this.scopes = builder.scopes;
        this.timeout = builder.timeout;
        if (builder.discovery != null && builder.tokenExchange != null) {
            this.discovery = builder.discovery;
            this.tokenExchange = builder.tokenExchange;
        }
        else {
            HttpClient httpClient = builder.httpClient != null
                    ? builder.httpClient
                    : HttpClient.newBuilder()
                        .connectTimeout(builder.connectTimeout)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            this.discovery = builder.discovery != null ? builder.discovery : new OidcDiscovery(httpClient);
            this.tokenExchange = builder.tokenExchange != null ? builder.tokenExchange : new OidcTokenExchange(httpClient);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Perform the OIDC login flow.
     *
     * <p>Steps:
     * <ol>
     *   <li>Discover OIDC endpoints from {@code .well-known/openid-configuration}</li>
     *   <li>Generate PKCE code verifier and challenge</li>
     *   <li>Start a local callback server on an ephemeral port</li>
     *   <li>Build the authorization URL and invoke {@code browserLauncher}</li>
     *   <li>Wait for the browser callback with the authorization code</li>
     *   <li>Exchange the code for an access token</li>
     * </ol>
     *
     * @param browserLauncher a callback that receives the authorization URL and opens it in a browser
     * @return the OAuth2 access token
     * @throws Exception if any step fails
     */
    public String login(Consumer<String> browserLauncher) throws Exception {
        // 1. OIDC discovery
        OidcConfig config = discovery.discover(endpoint);

        // 2. PKCE
        String verifier = PkceUtil.generateVerifier();
        String challenge = PkceUtil.computeChallenge(verifier);
        String state = PkceUtil.generateState();

        // 3. Start callback server
        try (OidcCallbackServer server = new OidcCallbackServer(state)) {
            String redirectUri = server.getRedirectUri();

            // 4. Build authorization URL and open browser
            String authUrl = config.getAuthorizationEndpoint()
                    + "?client_id=" + urlEncode(clientId)
                    + "&response_type=code"
                    + "&scope=" + urlEncode(scopes)
                    + "&redirect_uri=" + urlEncode(redirectUri)
                    + "&state=" + urlEncode(state)
                    + "&code_challenge=" + urlEncode(challenge)
                    + "&code_challenge_method=S256";

            browserLauncher.accept(authUrl);

            // 5. Wait for callback
            String code = server.waitForCode(timeout.toMillis(), TimeUnit.MILLISECONDS);

            // 6. Exchange code for token
            return tokenExchange.exchange(
                    config.getTokenEndpoint(), clientId, code, verifier, redirectUri);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Builder for {@link OidcLoginFlow}.
     */
    public static class Builder {
        private String endpoint;
        private String clientId;
        private String scopes = DEFAULT_SCOPES;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private HttpClient httpClient;
        private OidcDiscovery discovery;
        private OidcTokenExchange tokenExchange;

        private Builder() {}

        /**
         * Set the Platform API endpoint (required).
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Set the OAuth2 client ID (required).
         */
        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        /**
         * Set the OAuth2 scopes (default: {@code "openid profile email offline_access"}).
         */
        public Builder scopes(String scopes) {
            this.scopes = scopes;
            return this;
        }

        /**
         * Set the timeout for waiting for the browser callback (default: 5 minutes).
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Set the HTTP connect timeout (default: 10 seconds).
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Set a custom {@link HttpClient} for OIDC discovery and token exchange.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Set a custom {@link OidcDiscovery} (for testing).
         */
        public Builder discovery(OidcDiscovery discovery) {
            this.discovery = discovery;
            return this;
        }

        /**
         * Set a custom {@link OidcTokenExchange} (for testing).
         */
        public Builder tokenExchange(OidcTokenExchange tokenExchange) {
            this.tokenExchange = tokenExchange;
            return this;
        }

        public OidcLoginFlow build() {
            if (endpoint == null || endpoint.isEmpty())
                throw new IllegalStateException("endpoint is required");
            if (clientId == null || clientId.isEmpty())
                throw new IllegalStateException("clientId is required");
            return new OidcLoginFlow(this);
        }
    }
}
