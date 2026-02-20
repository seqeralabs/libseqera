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

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;


import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe manager for JWT token lifecycle including authentication header injection and automatic token refresh.
 * 
 * <p>This class handles all aspects of JWT token management for HTTP requests:
 * <ul>
 *   <li><strong>Header Injection</strong>: Automatically adds Authorization headers to HTTP requests</li>
 *   <li><strong>Token Validation</strong>: Validates JWT token format (header.payload.signature)</li>
 *   <li><strong>Token Refresh</strong>: Implements OAuth 2.0 refresh token flow</li>
 *   <li><strong>Thread Safety</strong>: Uses synchronized blocks and ConcurrentHashMap for safe concurrent access</li>
 * </ul>
 * 
 * <p><strong>Token Refresh Process:</strong><br>
 * When {@link #refreshToken()} or {@link #refreshTokenAsync()} is called, the manager:
 * <ol>
 *   <li>Sends a POST request to the configured refresh URL</li>
 *   <li>Uses {@code grant_type=refresh_token} with the current refresh token</li>
 *   <li>Accepts response tokens in two formats:
 *       <ul>
 *         <li><strong>Cookies</strong>: JWT and JWT_REFRESH_TOKEN in Set-Cookie headers</li>
 *         <li><strong>JSON</strong>: access_token and refresh_token in response body</li>
 *       </ul>
 *   </li>
 *   <li>Updates internal token state atomically</li>
 * </ol>
 * 
 * <p><strong>Thread Safety:</strong><br>
 * All public methods are thread-safe:
 * <ul>
 *   <li>Token storage is managed through {@link HxTokenStore} (default: {@link HxMapTokenStore} backed by ConcurrentHashMap)</li>
 *   <li>Token updates use {@code synchronized} to ensure atomic read-modify-write</li>
 *   <li>Concurrent token refresh operations are coordinated per-key using {@link ConcurrentHashMap#computeIfAbsent}</li>
 * </ul>
 * 
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * HxConfig config = HxConfig.newBuilder()
 *     .bearerToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
 *     .refreshToken("your-refresh-token")
 *     .refreshTokenUrl("https://api.example.com/oauth/token")
 *     .build();
 *
 * HxTokenManager manager = new HxTokenManager(config);
 *
 * // Add auth header to requests
 * HttpRequest authenticatedRequest = manager.addAuthHeader(originalRequest);
 *
 * // Multi-session: use HxAuth for per-user token management
 * HxAuth userAuth = new DefaultHxAuth("user.jwt.token", "refresh-token", "https://example.com/oauth/token");
 * HxAuth current = manager.getAuth(userAuth);
 * HttpRequest userRequest = manager.addAuthHeader(originalRequest, current);
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see HxConfig
 * @see HxClient
 */
class HxTokenManager {

    private static final Logger log = LoggerFactory.getLogger(HxTokenManager.class);

    private static final Pattern JWT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String DEFAULT_TOKEN = "default-token";

    private final HxConfig config;
    private final HxTokenStore tokenStore;

    // Coordination for concurrent token refresh operations per key
    private final ConcurrentMap<String, CompletableFuture<HxAuth>> ongoingRefreshes = new ConcurrentHashMap<>();

    public HxTokenManager(HxConfig config) {
        this(config, new HxMapTokenStore());
    }

    public HxTokenManager(HxConfig config, HxTokenStore tokenStore) {
        this.config = config;
        this.tokenStore = tokenStore;

        // Store initial tokens from config using DEFAULT_KEY
        final String jwtToken = config.getJwtToken();
        final String refreshToken = config.getRefreshToken();
        if (jwtToken != null && !jwtToken.isEmpty()) {
            tokenStore.put(DEFAULT_TOKEN, new DefaultHxAuth(jwtToken, refreshToken, config.getRefreshTokenUrl()));
        }

        // Validate JWT token refresh configuration
        validateTokenRefreshConfig();
    }

    /**
     * Validates that JWT token refresh configuration follows the allowed patterns.
     * 
     * <p>This method enforces that JWT configuration must be one of:
     * <ul>
     *   <li><strong>JWT only</strong>: Just JWT token (no refresh capability)</li>
     *   <li><strong>Complete JWT refresh</strong>: JWT token + refresh token + refresh URL (full refresh capability)</li>
     *   <li><strong>No JWT</strong>: No JWT components at all</li>
     * </ul>
     * 
     * <p>Partial configurations are not allowed as they would lead to confusing runtime behavior.
     * 
     * @throws IllegalArgumentException if the JWT configuration is incomplete or inconsistent
     */
    private void validateTokenRefreshConfig() {
        final String jwtToken = getCurrentJwtToken();
        final String refreshToken = getCurrentRefreshToken();
        final boolean hasJwtToken = jwtToken != null && !jwtToken.trim().isEmpty();
        final boolean hasRefreshToken = refreshToken != null && !refreshToken.trim().isEmpty();
        final boolean hasRefreshUrl = config.getRefreshTokenUrl() != null && !config.getRefreshTokenUrl().trim().isEmpty();
        
        // Count how many refresh components we have
        int refreshComponents = 0;
        if (hasRefreshToken) refreshComponents++;
        if (hasRefreshUrl) refreshComponents++;
        
        // Valid configurations:
        // 1. JWT only: hasJwtToken=true, refreshComponents=0
        // 2. Complete refresh: hasJwtToken=true, refreshComponents=2
        // 3. No JWT: hasJwtToken=false, refreshComponents=0
        
        if (hasJwtToken) {
            if (refreshComponents == 0) {
                log.trace("JWT token configured without refresh capability");
            } else if (refreshComponents == 2) {
                log.trace("JWT token refresh is fully configured and ready");
            } else {
                throw new IllegalArgumentException("JWT token refresh configuration is incomplete. Either provide only JWT token, or provide JWT token + refresh token + refresh URL");
            }
        } else {
            if (refreshComponents == 0) {
                log.trace("No JWT token authentication configured");
            } else {
                throw new IllegalArgumentException("Refresh components are configured without JWT token. Either remove refresh components or add JWT token");
            }
        }
    }

    /**
     * Adds an Authorization header to the given HTTP request using the configured authentication method.
     * 
     * <p>This method handles authentication in the following priority order:
     * <ol>
     *   <li><strong>JWT Bearer Token</strong>: If a JWT token is configured, adds "Bearer token" header</li>
     *   <li><strong>Basic Authentication</strong>: If username/password are configured, adds "Basic base64(user:pass)" header</li>
     *   <li><strong>No Authentication</strong>: Returns the original request unchanged</li>
     * </ol>
     * 
     * <p><strong>JWT Token Handling:</strong>
     * <ul>
     *   <li>Adds "Bearer " prefix to the token if not already present</li>
     *   <li>Uses the current JWT token (which may have been refreshed)</li>
     * </ul>
     * 
     * <p><strong>Basic Authentication Handling:</strong>
     * <ul>
     *   <li>Combines username and password with ":" separator</li>
     *   <li>Base64 encodes the credentials</li>
     *   <li>Adds "Basic " prefix to the encoded credentials</li>
     * </ul>
     * 
     * @param originalRequest the original HTTP request
     * @return a new HttpRequest with Authorization header, or the original request if no authentication is configured
     */
    public HttpRequest addAuthHeader(HttpRequest originalRequest) {
        // Priority 1: JWT Bearer token
        HttpRequest result = addAuthHeader(originalRequest, getDefaultAuth());

        // Priority 2: Basic authentication fallback (if no JWT auth was applied)
        if (result == originalRequest && hasBasicAuth()) {
            final String encodedCredentials = Base64.getEncoder().encodeToString(config.getBasicAuthToken().getBytes());
            return HttpRequest.newBuilder(originalRequest, (name, value) -> true)
                    .header("Authorization", "Basic " + encodedCredentials)
                    .build();
        }

        return result;
    }

    /**
     * Checks whether token refresh is possible with the current configuration.
     *
     * @return true if both refresh token and refresh URL are configured, false otherwise
     */
    public boolean canRefreshToken() {
        return canRefreshToken(getDefaultAuth());
    }

    /**
     * Attempts to refresh the JWT token synchronously using the configured refresh token.
     * 
     * <p>This method performs an OAuth 2.0 refresh token flow:
     * <ul>
     *   <li>Sends a POST request to the configured refresh token URL</li>
     *   <li>Uses application/x-www-form-urlencoded content type</li>
     *   <li>Includes grant_type=refresh_token and the current refresh token</li>
     *   <li>Extracts new tokens from response cookies or JSON body</li>
     *   <li>Updates the current JWT and refresh tokens if successful</li>
     * </ul>
     * 
     * <p>This method is thread-safe and uses write locks to prevent concurrent modifications.
     * 
     * @return true if token refresh was successful, false otherwise
     */
    public boolean refreshToken() {
        return refreshToken(getDefaultAuth()) != null;
    }

    /**
     * Gets or initiates a coordinated token refresh operation to prevent concurrent refreshes.
     *
     * <p>This method delegates to {@link #getOrRefreshTokenAsync(HxAuth)} using the default token.
     *
     * @return a CompletableFuture that completes with true if refresh was successful, false otherwise
     */
    public CompletableFuture<Boolean> getOrRefreshTokenAsync() {
        return getOrRefreshTokenAsync(getDefaultAuth()).thenApply(result -> result != null);
    }

    /**
     * Gets or initiates a coordinated token refresh operation for the given auth.
     *
     * <p>This method ensures that multiple concurrent requests that need token refresh will
     * share a single refresh operation instead of each performing their own refresh. This:
     * <ul>
     *   <li>Reduces unnecessary API calls to the OAuth server</li>
     *   <li>Prevents potential race conditions with token invalidation</li>
     *   <li>Improves performance under high concurrency</li>
     *   <li>Avoids hitting rate limits on token refresh endpoints</li>
     * </ul>
     *
     * @param auth the authentication data to refresh
     * @return a CompletableFuture that completes with the refreshed HxAuth, or null if refresh failed
     */
    public CompletableFuture<HxAuth> getOrRefreshTokenAsync(HxAuth auth) {
        if (!canRefreshToken(auth)) {
            log.warn("Cannot refresh token: refreshToken={} refreshTokenUrl={}",
                    (auth != null && auth.refreshToken() != null), config.getRefreshTokenUrl());
            return CompletableFuture.completedFuture(null);
        }

        final String key = auth.id();

        // Use computeIfAbsent for atomic coordination - only first caller creates the future
        return ongoingRefreshes.computeIfAbsent(key, k -> {
            log.trace("Starting coordinated token refresh for key {}", k);
            CompletableFuture<HxAuth> future = CompletableFuture.supplyAsync(() -> doRefreshToken(auth));
            future.whenComplete((result, throwable) -> {
                        // Use two-arg remove to only remove if still our future,
                        // avoiding accidental removal of a concurrent caller's future
                        ongoingRefreshes.remove(key, future);
                        if (throwable != null) {
                            log.error("Coordinated token refresh failed for key {}: {}", key, throwable.getMessage(), throwable);
                        } else {
                            log.trace("Coordinated token refresh completed for key {} with result: {}", key, (result != null));
                        }
                    });
            return future;
        });
    }

    /**
     * Attempts to refresh the JWT token asynchronously using the configured refresh token.
     *
     * <p>This method performs the same OAuth 2.0 refresh token flow as {@link #refreshToken()}
     * but returns a CompletableFuture for non-blocking execution.
     *
     * @return a CompletableFuture that completes with true if refresh was successful, false otherwise
     */
    public CompletableFuture<Boolean> refreshTokenAsync() {
        return refreshTokenAsync(getDefaultAuth()).thenApply(result -> result != null);
    }

    /**
     * Internal method that performs the actual token refresh HTTP request.
     *
     * <p>This method delegates to {@link #doRefreshTokenInternal(String, HxAuth)} using the default token.
     *
     * @return true if the token refresh was successful, false otherwise
     */
    protected boolean doRefreshToken() {
        final HxAuth auth = getDefaultAuth();
        return auth != null && doRefreshToken(auth) != null;
    }

    /**
     * Extracts a cookie from the given cookie manager by name.
     *
     * @param cookies the cookie manager to search
     * @param cookieName the name of the cookie to extract (e.g., "JWT", "JWT_REFRESH_TOKEN")
     * @return the HttpCookie if found, null otherwise
     */
    protected HttpCookie getCookie(CookieManager cookies, String cookieName) {
        for (HttpCookie cookie : cookies.getCookieStore().getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    /**
     * Validates that a token string conforms to JWT format.
     * 
     * <p>A valid JWT token consists of three Base64-encoded parts separated by dots:
     * header.payload.signature. This method validates the format but does not verify
     * the cryptographic signature or token expiration.
     * 
     * <p>The method handles tokens with or without the "Bearer " prefix.
     * 
     * @param token the token string to validate, may be null
     * @return true if the token has valid JWT format, false otherwise
     */
    protected boolean isValidJwtToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        final String cleanToken = token.startsWith(BEARER_PREFIX) ? token.substring(BEARER_PREFIX.length()) : token;
        return JWT_PATTERN.matcher(cleanToken).matches();
    }

    /**
     * Gets the current JWT token.
     *
     * @return the current JWT token, or null if not set
     */
    public String getCurrentJwtToken() {
        final HxAuth auth = tokenStore.get(DEFAULT_TOKEN);
        return auth != null ? auth.accessToken() : null;
    }

    /**
     * Gets the current refresh token.
     *
     * @return the current refresh token, or null if not set
     */
    public String getCurrentRefreshToken() {
        final HxAuth auth = tokenStore.get(DEFAULT_TOKEN);
        return auth != null ? auth.refreshToken() : null;
    }

    /**
     * Returns the configured basic authentication token, if any.
     * 
     * <p>This method provides read-only access to the basic authentication token
     * configured in the HxConfig. This can be useful for logging, debugging, or
     * conditional authentication logic.
     * 
     * @return the basic authentication token in "username:password" format, or null if not configured
     */
    public String getBasicAuthToken() {
        return config.getBasicAuthToken();
    }

    /**
     * Checks if basic authentication is properly configured.
     * 
     * <p>Basic authentication is considered properly configured when the
     * token is non-null, non-empty, and not just a colon (which would be
     * the result of empty username and password).
     * 
     * @return true if basic auth token is configured, false otherwise
     */
    public boolean hasBasicAuth() {
        return config.getBasicAuthToken() != null && 
               !config.getBasicAuthToken().isEmpty() &&
               !":".equals(config.getBasicAuthToken());
    }

    /**
     * Updates both JWT and refresh tokens atomically.
     *
     * <p>This method validates the JWT token format before updating and will not
     * update an invalid JWT token. The refresh token is updated if provided,
     * regardless of format (as refresh tokens may have different formats).
     *
     * @param jwtToken the new JWT token to set, may be null
     * @param refreshToken the new refresh token to set, may be null
     */
    // Note: synchronized protects the read-modify-write on DEFAULT_TOKEN only.
    // Multi-session refreshes (doRefreshTokenInternal) operate on separate keys
    // and are coordinated via ongoingRefreshes/computeIfAbsent.
    synchronized void updateTokens(String jwtToken, String refreshToken) {
        final HxAuth currentAuth = tokenStore.get(DEFAULT_TOKEN);
        String newToken = (currentAuth != null) ? currentAuth.accessToken() : null;
        String newRefresh = (currentAuth != null) ? currentAuth.refreshToken() : null;

        if (jwtToken != null && isValidJwtToken(jwtToken)) {
            newToken = jwtToken;
            log.trace("JWT token updated");
        }
        if (refreshToken != null) {
            newRefresh = refreshToken;
            log.trace("Refresh token updated");
        }

        if (newToken != null) {
            tokenStore.put(DEFAULT_TOKEN, new DefaultHxAuth(newToken, newRefresh, config.getRefreshTokenUrl()));
        }
    }

    // ========================================================================
    // Multi-user token management methods
    // ========================================================================

    /**
     * Gets the current authentication data for the given auth from the token store.
     *
     * <p>If the auth has been refreshed, this returns the updated tokens. If not found
     * in the store, the original auth is stored and returned for future use.
     *
     * @param auth the original authentication data
     * @return the current authentication data (may contain refreshed tokens)
     */
    public HxAuth getAuth(HxAuth auth) {
        if (auth == null) {
            return null;
        }
        final String key = auth.id();
        return tokenStore.putIfAbsent(key, auth);
    }

    /**
     * Adds an Authorization header to the given HTTP request using the token from the provided {@link HxAuth}.
     *
     * <p>This method retrieves the latest token for the given auth from the token store (in case it was
     * refreshed) and adds the Bearer Authorization header to the request.
     *
     * @param originalRequest the original HTTP request
     * @param auth the authentication data containing the token
     * @return a new HttpRequest with Authorization header
     */
    public HttpRequest addAuthHeader(HttpRequest originalRequest, HxAuth auth) {
        final HxAuth currentAuth = getAuth(auth);
        final String jwtToken = currentAuth != null ? currentAuth.accessToken() : null;

        if (jwtToken != null && !jwtToken.isEmpty()) {
            final String headerValue = jwtToken.startsWith(BEARER_PREFIX) ? jwtToken : BEARER_PREFIX + jwtToken;
            return HttpRequest.newBuilder(originalRequest, (name, value) -> true)
                    .header("Authorization", headerValue)
                    .build();
        }

        return originalRequest;
    }

    /**
     * Checks whether token refresh is possible for the given auth.
     *
     * @param auth the authentication data
     * @return true if both refresh token and refresh URL are configured, false otherwise
     */
    public boolean canRefreshToken(HxAuth auth) {
        if (auth == null) {
            return false;
        }
        // Use tokenStore.get() to avoid the side effect of storing the auth
        final HxAuth currentAuth = tokenStore.get(auth.id());
        final HxAuth effectiveAuth = currentAuth != null ? currentAuth : auth;
        return effectiveAuth.refreshToken() != null && resolveRefreshUrl(effectiveAuth) != null;
    }

    /**
     * Resolves the refresh URL for the given auth, falling back to the global config.
     *
     * @param auth the authentication data
     * @return the refresh URL, or null if none is configured
     */
    private String resolveRefreshUrl(HxAuth auth) {
        if (auth != null && auth.refreshUrl() != null) {
            return auth.refreshUrl();
        }
        return config.getRefreshTokenUrl();
    }

    /**
     * Attempts to refresh the JWT token synchronously for the given auth.
     *
     * <p>This method performs an OAuth 2.0 refresh token flow for a specific user session.
     * On success, the new tokens are stored in the token store automatically.
     *
     * @param auth the authentication data containing the refresh token
     * @return the updated {@link HxAuth} with new tokens, or null if refresh failed
     */
    public HxAuth refreshToken(HxAuth auth) {
        if (!canRefreshToken(auth)) {
            log.warn("Cannot refresh token for auth: refreshToken={} refreshTokenUrl={}",
                    (auth != null && auth.refreshToken() != null), config.getRefreshTokenUrl());
            return null;
        }
        return doRefreshToken(auth);
    }

    /**
     * Attempts to refresh the JWT token asynchronously for the given auth.
     *
     * @param auth the authentication data containing the refresh token
     * @return a CompletableFuture that completes with the updated {@link HxAuth}, or null if refresh failed
     */
    public CompletableFuture<HxAuth> refreshTokenAsync(HxAuth auth) {
        if (!canRefreshToken(auth)) {
            log.warn("Cannot refresh token asynchronously for auth: refreshToken={} refreshTokenUrl={}",
                    (auth != null && auth.refreshToken() != null), config.getRefreshTokenUrl());
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> doRefreshToken(auth));
    }

    /**
     * Performs the actual token refresh for a specific auth.
     *
     * <p>This method delegates to {@link #doRefreshTokenInternal(String, HxAuth)} using the auth's key.
     *
     * @param auth the authentication data containing the refresh token
     * @return the updated {@link HxAuth} with new tokens, or null if refresh failed
     */
    protected HxAuth doRefreshToken(HxAuth auth) {
        final HxAuth currentAuth = getAuth(auth);
        return doRefreshTokenInternal(auth.id(), currentAuth);
    }

    /**
     * Internal method that performs the actual token refresh HTTP request.
     *
     * @param key the key to use for storing the refreshed token
     * @param auth the authentication data containing the refresh token
     * @return the updated {@link HxAuth} with new tokens, or null if refresh failed
     */
    protected HxAuth doRefreshTokenInternal(String key, HxAuth auth) {
        try {
            final var refreshUrl = URI.create(resolveRefreshUrl(auth));
            log.trace("Attempting to refresh JWT token for key {} at URL: {}", key, refreshUrl);

            // Create per-refresh CookieManager and HttpClient to avoid cross-user cookie leaking
            final CookieManager cookieManager = (config.getRefreshCookiePolicy() != null)
                    ? new CookieManager(null, config.getRefreshCookiePolicy())
                    : new CookieManager();
            final HttpClient refreshHttpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .cookieHandler(cookieManager)
                    .connectTimeout(config.getTokenRefreshTimeout())
                    .build();

            final String body = "grant_type=refresh_token&refresh_token=" +
                    URLEncoder.encode(auth.refreshToken(), StandardCharsets.UTF_8);

            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(refreshUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(config.getTokenRefreshTimeout())
                    .build();

            final HttpResponse<String> response = refreshHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.trace("Token refresh response for key {}: [{}]", key, response.statusCode());

            if (response.statusCode() == 200) {
                final HxAuth newAuth = extractAuthFromResponse(response, cookieManager, auth);
                if (newAuth != null) {
                    log.debug("JWT token refresh completed for key {}", key);
                    tokenStore.put(key, newAuth);
                    return newAuth;
                }
            } else {
                log.warn("Token refresh failed for key {} with status {}: {}",
                        key, response.statusCode(), response.body());
            }
            return null;
        } catch (Exception e) {
            log.error("Error refreshing JWT token for key {}: {}", key, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extracts tokens from the HTTP response and creates a new {@link HxAuth}.
     *
     * @param response the HTTP response from the token refresh request
     * @param cookieManager the cookie manager used for the refresh request
     * @param auth the original authentication data (used to preserve refresh token if not returned)
     * @return a new {@link HxAuth} with updated tokens, or null if extraction failed
     */
    protected HxAuth extractAuthFromResponse(HttpResponse<String> response, CookieManager cookieManager, HxAuth auth) {
        // Try cookies first
        final String newToken = extractTokenFromCookies(cookieManager);
        if (newToken != null) {
            final String newRefresh = extractRefreshFromCookies(cookieManager);
            HxAuth result = auth.withToken(newToken);
            if (newRefresh != null) {
                result = result.withRefresh(newRefresh);
            }
            return result;
        }

        // Fall back to JSON response
        return extractAuthFromJson(response, auth);
    }

    private String extractTokenFromCookies(CookieManager cookieManager) {
        final HttpCookie cookie = getCookie(cookieManager, "JWT");
        return (cookie != null) ? cookie.getValue() : null;
    }

    private String extractRefreshFromCookies(CookieManager cookieManager) {
        final HttpCookie cookie = getCookie(cookieManager, "JWT_REFRESH_TOKEN");
        return (cookie != null) ? cookie.getValue() : null;
    }

    private HxAuth extractAuthFromJson(HttpResponse<String> response, HxAuth auth) {
        try {
            final JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("access_token")) {
                return null;
            }
            final String accessToken = json.get("access_token").getAsString();
            if (!isValidJwtToken(accessToken)) {
                return null;
            }
            HxAuth result = auth.withToken(accessToken);
            if (json.has("refresh_token")) {
                result = result.withRefresh(json.get("refresh_token").getAsString());
            }
            return result;
        } catch (Exception e) {
            log.trace("Response is not valid JSON");
            return null;
        }
    }

    /**
     * Returns the token store used by this manager.
     *
     * @return the {@link HxTokenStore} instance
     */
    public HxTokenStore getTokenStore() {
        return tokenStore;
    }

    /**
     * Returns the default authentication data configured from HxConfig.
     *
     * <p>This is the auth stored under the default key, initialized from
     * the JWT token and refresh token configured in HxConfig.
     *
     * @return the default {@link HxAuth}, or null if no default token is configured
     */
    public HxAuth getDefaultAuth() {
        return tokenStore.get(DEFAULT_TOKEN);
    }
}
