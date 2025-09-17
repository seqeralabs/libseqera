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

import java.net.CookiePolicy;
import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

import io.seqera.http.auth.AuthenticationCallback;
import io.seqera.util.retry.Retryable;

/**
 * Configuration class for {@link HxClient} that defines retry behavior and JWT token settings.
 * 
 * <p>This class implements {@link io.seqera.util.retry.Retryable.Config} to provide retry configuration
 * and extends it with HTTP-specific settings like status codes to retry and JWT token management.
 * 
 * <p><strong>Default Configuration:</strong>
 * <ul>
 *   <li>Initial delay: 500ms</li>
 *   <li>Maximum delay: 30 seconds</li>
 *   <li>Maximum attempts: 5</li>
 *   <li>Jitter: 0.25 (25% random variation)</li>
 *   <li>Backoff multiplier: 2.0 (exponential)</li>
 *   <li>Retry status codes: 429, 500, 502, 503, 504</li>
 *   <li>Token refresh timeout: 30 seconds</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Default configuration
 * HxConfig config = HxConfig.newBuilder().build();
 * 
 * // Custom retry configuration
 * HxConfig config = HxConfig.newBuilder()
 *     .withMaxAttempts(3)
 *     .withDelay(Duration.ofSeconds(1))
 *     .withRetryStatusCodes(Set.of(429, 503))
 *     .build();
 * 
 * // With JWT token configuration
 * HxConfig config = HxConfig.newBuilder()
 *     .withJwtToken("your-jwt-token")
 *     .withRefreshToken("your-refresh-token")
 *     .withRefreshTokenUrl("https://api.example.com/oauth/token")
 *     .build();
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class HxConfig implements Retryable.Config {

    private static final Predicate<? extends Throwable> DEFAULT_RETRY_COND = throwable -> throwable instanceof java.io.IOException;

    private Duration delay = Duration.ofMillis(500);
    private Duration maxDelay = Duration.ofSeconds(30);
    private int maxAttempts = 5;
    private double jitter = 0.25d;
    private double multiplier = 2.0;

    private Predicate<? extends Throwable> retryCondition = DEFAULT_RETRY_COND;

    private Set<Integer> retryStatusCodes = Set.of(429, 500, 502, 503, 504);

    private String jwtToken;
    private String refreshToken;  
    private String refreshTokenUrl;
    private Duration tokenRefreshTimeout = Duration.ofSeconds(30);

    private String basicAuthToken;

    private boolean wwwAuthenticateEnabled = false;
    private AuthenticationCallback authenticationCallback;

    private CookiePolicy refreshCookiePolicy;

    @Override
    public Duration getDelay() {
        return delay;
    }

    @Override  
    public Duration getMaxDelay() {
        return maxDelay;
    }

    @Override
    public int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public double getJitter() {
        return jitter;
    }

    @Override
    public double getMultiplier() {
        return multiplier;
    }

    public Set<Integer> getRetryStatusCodes() {
        return retryStatusCodes;
    }

    public Predicate<? extends Throwable> getRetryCondition() {
        return retryCondition;
    }

    public String getJwtToken() {
        return jwtToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getRefreshTokenUrl() {
        return refreshTokenUrl;
    }

    public Duration getTokenRefreshTimeout() {
        return tokenRefreshTimeout;
    }

    public boolean isWwwAuthenticateEnabled() {
        return wwwAuthenticateEnabled;
    }

    public AuthenticationCallback getAuthenticationCallback() {
        return authenticationCallback;
    }

    public String getBasicAuthToken() {
        return basicAuthToken;
    }

    public CookiePolicy getRefreshCookiePolicy() {
        return refreshCookiePolicy;
    }

    /**
     * Creates a new builder instance for constructing HttpConfig objects.
     * 
     * @return a new Builder with default values pre-populated
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder class for constructing HttpConfig instances with a fluent API.
     * 
     * <p>All builder methods return the builder instance to enable method chaining.
     * The {@link #build()} method creates the final immutable configuration object.
     */
    public static class Builder {
        private Duration delay = Duration.ofMillis(500);
        private Duration maxDelay = Duration.ofSeconds(30);
        private int maxAttempts = 5;
        private double jitter = 0.25d;
        private double multiplier = 2.0;
        private Predicate<? extends Throwable> retryCondition = DEFAULT_RETRY_COND;
        private Set<Integer> retryStatusCodes = Set.of(429, 500, 502, 503, 504);
        private String bearerToken;
        private String refreshToken;
        private String refreshTokenUrl;
        private Duration tokenRefreshTimeout = Duration.ofSeconds(30);
        private String basicAuthToken;
        private boolean wwwAuthenticationEnabled = false;
        private AuthenticationCallback wwwAuthenticationCallback;
        private CookiePolicy refreshCookiePolicy;

        /**
         * Sets the initial retry delay duration.
         * 
         * @param delay the initial delay between retries
         * @return this builder instance for method chaining
         */
        public Builder delay(Duration delay) {
            this.delay = delay;
            return this;
        }
        
        /**
         * @deprecated Use {@link #delay(Duration)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withDelay(Duration delay) {
            this.delay = delay;
            return this;
        }

        /**
         * Sets the maximum retry delay duration.
         * 
         * @param maxDelay the maximum delay between retries
         * @return this builder instance for method chaining
         */
        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }
        
        /**
         * @deprecated Use {@link #maxDelay(Duration)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        /**
         * Sets the maximum number of retry attempts.
         * 
         * @param maxAttempts maximum retry attempts (must be positive)
         * @return this builder instance for method chaining
         */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        
        /**
         * @deprecated Use {@link #maxAttempts(int)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /**
         * Sets the jitter factor for retry delays.
         * 
         * @param jitter the jitter factor (0.0 to 1.0)
         * @return this builder instance for method chaining
         */
        public Builder jitter(double jitter) {
            this.jitter = jitter;
            return this;
        }
        
        /**
         * @deprecated Use {@link #jitter(double)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withJitter(double jitter) {
            this.jitter = jitter;
            return this;
        }

        /**
         * Sets the backoff multiplier for retry delays.
         * 
         * @param multiplier the backoff multiplier
         * @return this builder instance for method chaining
         */
        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }
        
        /**
         * @deprecated Use {@link #multiplier(double)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withMultiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        /**
         * Sets the retry condition predicate.
         * 
         * @param condition the condition to determine if an exception should trigger a retry
         * @return this builder instance for method chaining
         */
        public Builder retryCondition(Predicate<? extends Throwable> condition) {
            this.retryCondition = condition;
            return this;
        }
        
        /**
         * @deprecated Use {@link #retryCondition(Predicate)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withRetryCondition(Predicate<? extends Throwable> condition) {
            this.retryCondition = condition;
            return this;
        }

        /**
         * Sets the HTTP status codes that should trigger a retry.
         * 
         * @param retryStatusCodes the set of HTTP status codes to retry on
         * @return this builder instance for method chaining
         */
        public Builder retryStatusCodes(Set<Integer> retryStatusCodes) {
            this.retryStatusCodes = retryStatusCodes;
            return this;
        }
        
        /**
         * @deprecated Use {@link #retryStatusCodes(Set)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withRetryStatusCodes(Set<Integer> retryStatusCodes) {
            this.retryStatusCodes = retryStatusCodes;
            return this;
        }

        /**
         * Sets the Bearer/JWT token for authentication.
         * 
         * @param jwtToken the Bearer token to use for authentication
         * @return this builder instance for method chaining
         */
        public Builder bearerToken(String jwtToken) {
            this.bearerToken = jwtToken;
            return this;
        }
        
        /**
         * @deprecated Use {@link #bearerToken(String)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withBearerToken(String jwtToken) {
            this.bearerToken = jwtToken;
            return this;
        }

        /**
         * Sets the refresh token for automatic JWT token renewal.
         * 
         * @param refreshToken the refresh token
         * @return this builder instance for method chaining
         */
        public Builder refreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }
        
        /**
         * @deprecated Use {@link #refreshToken(String)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }

        /**
         * Sets the URL endpoint for refreshing JWT tokens.
         * 
         * @param refreshTokenUrl the token refresh endpoint URL
         * @return this builder instance for method chaining
         */
        public Builder refreshTokenUrl(String refreshTokenUrl) {
            this.refreshTokenUrl = refreshTokenUrl;
            return this;
        }
        
        /**
         * @deprecated Use {@link #refreshTokenUrl(String)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withRefreshTokenUrl(String refreshTokenUrl) {
            this.refreshTokenUrl = refreshTokenUrl;
            return this;
        }

        /**
         * Sets the timeout for token refresh operations.
         * 
         * @param tokenRefreshTimeout the timeout duration for refresh operations
         * @return this builder instance for method chaining
         */
        public Builder tokenRefreshTimeout(Duration tokenRefreshTimeout) {
            this.tokenRefreshTimeout = tokenRefreshTimeout;
            return this;
        }
        
        /**
         * @deprecated Use {@link #tokenRefreshTimeout(Duration)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withTokenRefreshTimeout(Duration tokenRefreshTimeout) {
            this.tokenRefreshTimeout = tokenRefreshTimeout;
            return this;
        }

        /**
         * Sets the token for HTTP Basic authentication.
         * 
         * <p>The token should be in the format "username:password". This will be Base64-encoded
         * automatically when creating the Authorization header.
         * 
         * <p><strong>Security Notes:</strong>
         * <ul>
         *   <li>Basic authentication sends credentials in every request</li>
         *   <li>Always use HTTPS when using basic authentication</li>
         *   <li>Consider using Bearer tokens for better security when possible</li>
         * </ul>
         * 
         * <p><strong>Authentication Priority:</strong><br>
         * If both JWT tokens and basic authentication are configured, JWT authentication
         * takes precedence. Basic authentication will only be used if no JWT token is available.
         * 
         * @param token the basic auth token in "username:password" format
         * @return this builder instance for method chaining
         */
        public Builder basicAuth(String token) {
            this.basicAuthToken = token;
            return this;
        }
        
        /**
         * @deprecated Use {@link #basicAuth(String)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withBasicAuth(String token) {
            this.basicAuthToken = token;
            return this;
        }

        /**
         * Sets both username and password for HTTP Basic authentication in a single call.
         * 
         * <p>This is a convenience method that combines the username and password into
         * the required "username:password" format internally.
         * 
         * <p><strong>Security Considerations:</strong>
         * <ul>
         *   <li>Store credentials securely and avoid hardcoding in source code</li>
         *   <li>Use environment variables or secure configuration stores</li>
         *   <li>Rotate credentials regularly according to security policies</li>
         *   <li>Always use HTTPS to protect credentials in transit</li>
         * </ul>
         * 
         * @param username the username for basic authentication
         * @param password the password for basic authentication
         * @return this builder instance for method chaining
         */
        public Builder basicAuth(String username, String password) {
            this.basicAuthToken = username + ":" + password;
            return this;
        }
        
        /**
         * @deprecated Use {@link #basicAuth(String, String)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withBasicAuth(String username, String password) {
            this.basicAuthToken = username + ":" + password;
            return this;
        }

        /**
         * Enables or disables automatic WWW-Authenticate challenge handling for 401 responses.
         * 
         * <p>When enabled, HxClient will automatically handle 401 Unauthorized responses that
         * include WWW-Authenticate headers by attempting to provide appropriate credentials
         * or falling back to anonymous authentication.
         * 
         * <p><strong>Supported Authentication Schemes:</strong>
         * <ul>
         *   <li><strong>Basic</strong>: HTTP Basic authentication with username/password</li>
         *   <li><strong>Bearer</strong>: Bearer token authentication (OAuth2/JWT)</li>
         * </ul>
         * 
         * <p><strong>When to Enable:</strong>
         * <ul>
         *   <li>Accessing APIs that require authentication challenges (e.g., Docker registries)</li>
         *   <li>Services that use WWW-Authenticate for anonymous access tokens</li>
         *   <li>Applications that need automatic credential handling</li>
         * </ul>
         * 
         * <p><strong>Security Considerations:</strong><br>
         * Enabling this feature means the client will automatically send credentials
         * in response to authentication challenges. Ensure your AuthenticationCallback
         * properly validates realms and only provides credentials to trusted endpoints.
         * 
         * @param value true to enable WWW-Authenticate handling, false to disable
         * @return this builder instance for method chaining
         */
        public Builder wwwAuthentication(boolean value) {
            this.wwwAuthenticationEnabled = value;
            return this;
        }
        
        /**
         * @deprecated Use {@link #wwwAuthentication(boolean)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withWwwAuthentication(boolean value) {
            this.wwwAuthenticationEnabled = value;
            return this;
        }

        /**
         * Sets the callback for providing authentication credentials during WWW-Authenticate challenges.
         * 
         * <p>This callback is invoked when a 401 Unauthorized response includes WWW-Authenticate
         * headers and WWW-Authenticate handling is enabled. The callback should examine the
         * authentication scheme and realm to determine if appropriate credentials are available.
         * 
         * <p><strong>Callback Behavior:</strong>
         * <ul>
         *   <li>Return properly formatted credentials for the scheme/realm</li>
         *   <li>Return null if no credentials are available (triggers anonymous auth fallback)</li>
         *   <li>Throw an exception if credentials cannot be safely retrieved</li>
         * </ul>
         * 
         * <p><strong>Credential Format Requirements:</strong>
         * <ul>
         *   <li><strong>Basic</strong>: Base64-encoded "username:password" string</li>
         *   <li><strong>Bearer</strong>: Token value without "Bearer " prefix</li>
         * </ul>
         * 
         * <p><strong>Example Implementation:</strong>
         * <pre>{@code
         * .wwwAuthenticationCallback((scheme, realm) -> {
         *     if (scheme == AuthenticationScheme.BASIC && "Protected Area".equals(realm)) {
         *         return Base64.getEncoder().encodeToString("user:pass".getBytes());
         *     } else if (scheme == AuthenticationScheme.BEARER && "api".equals(realm)) {
         *         return getApiToken(); // Your token retrieval logic
         *     }
         *     return null; // Fall back to anonymous authentication
         * })
         * }</pre>
         * 
         * @param value the authentication callback, or null to rely only on anonymous authentication
         * @return this builder instance for method chaining
         */
        public Builder wwwAuthenticationCallback(AuthenticationCallback value) {
            this.wwwAuthenticationCallback = value;
            return this;
        }
        
        /**
         * @deprecated Use {@link #wwwAuthenticationCallback(AuthenticationCallback)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withWwwAuthenticationCallback(AuthenticationCallback value) {
            this.wwwAuthenticationCallback = value;
            return this;
        }

        /**
         * Sets the cookie policy for the refresh token HTTP client used by HxTokenManager.
         * 
         * <p>This policy controls cookie handling behavior specifically for token refresh operations.
         * The policy only affects the internal HTTP client used for JWT token refresh, not the main
         * HTTP client used for regular requests.
         * 
         * <p><strong>Cookie Policy Options:</strong>
         * <ul>
         *   <li><strong>CookiePolicy.ACCEPT_ALL</strong>: Accept all cookies</li>
         *   <li><strong>CookiePolicy.ACCEPT_NONE</strong>: Accept no cookies</li>
         *   <li><strong>CookiePolicy.ACCEPT_ORIGINAL_SERVER</strong>: Accept cookies only from original server</li>
         * </ul>
         * 
         * <p><strong>When to Use:</strong>
         * <ul>
         *   <li>OAuth servers that set authentication cookies during token refresh</li>
         *   <li>Services that require specific cookie handling for token endpoints</li>
         *   <li>Compliance with security policies that restrict cookie behavior</li>
         * </ul>
         * 
         * @param policy the cookie policy for refresh token operations, or null for default behavior
         * @return this builder instance for method chaining
         */
        public Builder refreshCookiePolicy(CookiePolicy policy) {
            this.refreshCookiePolicy = policy;
            return this;
        }
        
        /**
         * @deprecated Use {@link #refreshCookiePolicy(CookiePolicy)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withRefreshCookiePolicy(CookiePolicy policy) {
            this.refreshCookiePolicy = policy;
            return this;
        }

        /**
         * Configures retry settings from a generic Retryable.Config instance.
         * This allows building HttpConfig with retry configuration from other sources.
         * 
         * @param retryConfig the Retryable.Config to copy retry settings from
         * @return this builder instance for method chaining
         */
        public Builder retryConfig(Retryable.Config retryConfig) {
            if (retryConfig != null) {
                this.delay = retryConfig.getDelayAsDuration();
                this.maxDelay = retryConfig.getMaxDelayAsDuration();
                this.maxAttempts = retryConfig.getMaxAttempts();
                this.jitter = retryConfig.getJitter();
                this.multiplier = retryConfig.getMultiplier();
            }
            return this;
        }
        
        /**
         * @deprecated Use {@link #retryConfig(Retryable.Config)} instead. This method will be removed in a future version.
         */
        @Deprecated(since = "2.1.0", forRemoval = true)
        public Builder withRetryConfig(Retryable.Config retryConfig) {
            if (retryConfig != null) {
                this.delay = retryConfig.getDelayAsDuration();
                this.maxDelay = retryConfig.getMaxDelayAsDuration();
                this.maxAttempts = retryConfig.getMaxAttempts();
                this.jitter = retryConfig.getJitter();
                this.multiplier = retryConfig.getMultiplier();
            }
            return this;
        }

        /**
         * Builds and returns a new HxConfig instance with the configured values.
         * 
         * @return a new HxConfig with all builder settings applied
         */
        public HxConfig build() {
            HxConfig config = new HxConfig();
            config.delay = this.delay;
            config.maxDelay = this.maxDelay;
            config.maxAttempts = this.maxAttempts;
            config.jitter = this.jitter;
            config.multiplier = this.multiplier;
            config.retryCondition = this.retryCondition;
            config.retryStatusCodes = this.retryStatusCodes;
            config.jwtToken = this.bearerToken;
            config.refreshToken = this.refreshToken;
            config.refreshTokenUrl = this.refreshTokenUrl;
            config.tokenRefreshTimeout = this.tokenRefreshTimeout;
            config.basicAuthToken = this.basicAuthToken;
            config.wwwAuthenticateEnabled = this.wwwAuthenticationEnabled;
            config.authenticationCallback = this.wwwAuthenticationCallback;
            config.refreshCookiePolicy = this.refreshCookiePolicy;
            
            // Validate authentication configuration
            if (this.bearerToken != null && this.basicAuthToken != null) {
                throw new IllegalArgumentException("Cannot configure both JWT token and Basic authentication. Choose one authentication method.");
            }
            
            return config;
        }
    }
}
