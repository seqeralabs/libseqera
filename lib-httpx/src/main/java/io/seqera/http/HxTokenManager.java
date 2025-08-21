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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 *   <li><strong>Thread Safety</strong>: Uses read/write locks for safe concurrent access</li>
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
 * All public methods are thread-safe using a {@link ReentrantReadWriteLock}:
 * <ul>
 *   <li>Token reads use shared read locks for high concurrency</li>
 *   <li>Token updates use exclusive write locks for consistency</li>
 *   <li>Token refresh operations are synchronized to prevent duplicate refreshes</li>
 * </ul>
 * 
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * HttpClientConfig config = HttpClientConfig.builder()
 *     .withJwtToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
 *     .withRefreshToken("your-refresh-token")
 *     .withRefreshTokenUrl("https://api.example.com/oauth/token")
 *     .build();
 *     
 * JwtTokenManager manager = new JwtTokenManager(config);
 * 
 * // Add auth header to requests
 * HttpRequest authenticatedRequest = manager.addAuthHeader(originalRequest);
 * 
 * // Refresh tokens when needed
 * if (manager.canRefreshToken()) {
 *     boolean success = manager.refreshToken();
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see HxConfig
 * @see HxClient
 */
public class HxTokenManager {

    private static final Logger log = LoggerFactory.getLogger(HxTokenManager.class);
    
    private static final Pattern JWT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final String BEARER_PREFIX = "Bearer ";

    private final HxConfig config;
    private final HttpClient refreshHttpClient;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile String currentJwtToken;
    
    private volatile String currentRefreshToken;
    
    // Coordination for concurrent token refresh operations
    private volatile CompletableFuture<Boolean> currentRefresh = null;

    public HxTokenManager(HxConfig config) {
        this.config = config;
        this.currentJwtToken = config.getJwtToken();
        this.currentRefreshToken = config.getRefreshToken();
        this.refreshHttpClient = HttpClient.newBuilder()
                .connectTimeout(config.getTokenRefreshTimeout())
                .build();
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
        final String jwtToken = getCurrentJwtToken();
        if (jwtToken != null && !jwtToken.isEmpty()) {
            final String headerValue = jwtToken.startsWith(BEARER_PREFIX) ? jwtToken : BEARER_PREFIX + jwtToken;
            return HttpRequest.newBuilder(originalRequest, (name, value) -> true)
                    .header("Authorization", headerValue)
                    .build();
        }

        // Priority 2: Basic authentication
        if (config.getBasicAuthToken() != null && !config.getBasicAuthToken().isEmpty()) {
            final String encodedCredentials = Base64.getEncoder().encodeToString(config.getBasicAuthToken().getBytes());
            final String headerValue = "Basic " + encodedCredentials;
            return HttpRequest.newBuilder(originalRequest, (name, value) -> true)
                    .header("Authorization", headerValue)
                    .build();
        }

        // No authentication configured
        return originalRequest;
    }

    /**
     * Checks whether token refresh is possible with the current configuration.
     * 
     * @return true if both refresh token and refresh URL are configured, false otherwise
     */
    public boolean canRefreshToken() {
        return currentRefreshToken != null && config.getRefreshTokenUrl() != null;
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
        if (!canRefreshToken()) {
            log.warn("Cannot refresh token: refreshToken={} refreshTokenUrl={}", 
                    (currentRefreshToken != null), config.getRefreshTokenUrl());
            return false;
        }

        lock.writeLock().lock();
        try {
            return doRefreshToken();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets or initiates a coordinated token refresh operation to prevent concurrent refreshes.
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
     * <p>The coordination mechanism:
     * <ul>
     *   <li>First caller starts the refresh and gets a CompletableFuture</li>
     *   <li>Subsequent callers get the same CompletableFuture to wait on</li>
     *   <li>Once refresh completes, all waiters are notified with the same result</li>
     *   <li>Future state is reset after completion for next refresh cycle</li>
     * </ul>
     * 
     * @return a CompletableFuture that completes with true if refresh was successful, false otherwise
     */
    public CompletableFuture<Boolean> getOrRefreshTokenAsync() {
        if (!canRefreshToken()) {
            log.warn("Cannot refresh token: refreshToken={} refreshTokenUrl={}", 
                    (currentRefreshToken != null), config.getRefreshTokenUrl());
            return CompletableFuture.completedFuture(false);
        }

        // Check if there's already a refresh in progress
        CompletableFuture<Boolean> refresh = currentRefresh;
        if (refresh != null && !refresh.isDone()) {
            log.debug("Token refresh already in progress, waiting for completion");
            return refresh;
        }

        // Use double-checked locking to ensure only one thread starts the refresh
        synchronized(this) {
            refresh = currentRefresh;
            if (refresh != null && !refresh.isDone()) {
                log.debug("Token refresh already in progress (double-check), waiting for completion");
                return refresh;
            }

            // Start new refresh operation
            log.debug("Starting coordinated token refresh");
            currentRefresh = CompletableFuture.supplyAsync(() -> {
                lock.writeLock().lock();
                try {
                    return doRefreshToken();
                } finally {
                    lock.writeLock().unlock();
                }
            }).whenComplete((result, throwable) -> {
                // Reset the currentRefresh after completion to allow future refreshes
                synchronized(this) {
                    if (currentRefresh != null && currentRefresh.isDone()) {
                        currentRefresh = null;
                    }
                }
                
                if (throwable != null) {
                    log.error("Coordinated token refresh failed: " + throwable.getMessage(), throwable);
                } else {
                    log.debug("Coordinated token refresh completed with result: " + result);
                }
            });
            
            return currentRefresh;
        }
    }

    /**
     * Attempts to refresh the JWT token asynchronously using the configured refresh token.
     * 
     * <p>This method performs the same OAuth 2.0 refresh token flow as {@link #refreshToken()}
     * but returns a CompletableFuture for non-blocking execution.
     * 
     * <p>This method is thread-safe and uses write locks to prevent concurrent modifications.
     * 
     * @return a CompletableFuture that completes with true if refresh was successful, false otherwise
     */
    public CompletableFuture<Boolean> refreshTokenAsync() {
        if (!canRefreshToken()) {
            log.warn("Cannot refresh token asynchronously: refreshToken={} refreshTokenUrl={}", 
                    (currentRefreshToken != null), config.getRefreshTokenUrl());
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            lock.writeLock().lock();
            try {
                return doRefreshToken();
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    /**
     * Internal method that performs the actual token refresh HTTP request.
     * 
     * <p>This method:
     * <ul>
     *   <li>Creates a POST request with form-urlencoded body</li>
     *   <li>Includes grant_type=refresh_token and the URL-encoded refresh token</li>
     *   <li>Sends the request with the configured timeout</li>
     *   <li>Handles both cookie-based and JSON response formats</li>
     *   <li>Updates internal token state on success</li>
     * </ul>
     * 
     * <p>This method is called by both synchronous and asynchronous refresh methods
     * and assumes the caller has acquired the appropriate write lock.
     * 
     * @return true if the token refresh was successful, false otherwise
     */
    protected boolean doRefreshToken() {
        try {
            log.debug("Attempting to refresh JWT token using refresh token");
            
            final String body = "grant_type=refresh_token&refresh_token=" + 
                    URLEncoder.encode(currentRefreshToken, StandardCharsets.UTF_8.toString());
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getRefreshTokenUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(config.getTokenRefreshTimeout())
                    .build();

            final HttpResponse<String> response = refreshHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return handleRefreshResponse(response);
            } else {
                log.warn("Token refresh failed with status {}: {}", response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Error refreshing JWT token: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Processes the HTTP response from a token refresh request and extracts new tokens.
     * 
     * <p>This method supports multiple response formats:
     * <ul>
     *   <li><strong>Cookie-based</strong>: Extracts JWT and JWT_REFRESH_TOKEN from Set-Cookie headers</li>
     *   <li><strong>JSON-based</strong>: Parses JSON response body for access_token and refresh_token fields</li>
     * </ul>
     * 
     * <p>The method validates extracted JWT tokens using {@link #isValidJwtToken(String)}
     * and updates the internal token state atomically.
     * 
     * @param response the HTTP response from the token refresh request
     * @return true if tokens were successfully extracted and updated, false otherwise
     */
    protected boolean handleRefreshResponse(HttpResponse<String> response) {
        try {
            final String newJwtToken = extractTokenFromCookies(response, "JWT");
            final String newRefreshToken = extractTokenFromCookies(response, "JWT_REFRESH_TOKEN");

            if (newJwtToken != null) {
                log.trace("Successfully refreshed JWT token");
                currentJwtToken = newJwtToken;
                
                if (newRefreshToken != null) {
                    log.trace("Successfully refreshed refresh token");
                    currentRefreshToken = newRefreshToken;
                } else {
                    log.debug("No new refresh token in response, keeping existing one");
                }
                
                return true;
            } else {
                log.warn("No JWT token found in refresh response");
                
                try {
                    final JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (jsonResponse.has("access_token")) {
                        final String accessToken = jsonResponse.get("access_token").getAsString();
                        if (isValidJwtToken(accessToken)) {
                            log.trace("Successfully extracted JWT token from JSON response");
                            currentJwtToken = accessToken;
                            
                            if (jsonResponse.has("refresh_token")) {
                                currentRefreshToken = jsonResponse.get("refresh_token").getAsString();
                                log.trace("Successfully extracted refresh token from JSON response");
                            }
                            
                            return true;
                        }
                    }
                } catch (Exception jsonEx) {
                    log.debug("Response is not valid JSON, trying to parse as form data");
                }
                
                return false;
            }
        } catch (Exception e) {
            log.error("Error processing token refresh response: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extracts a token value from HTTP cookies in the response.
     * 
     * <p>This method searches through all Set-Cookie headers for a cookie with the
     * specified name and returns its value. The cookie value is extracted from the
     * first occurrence of "cookieName=value" format, ignoring any cookie attributes
     * like Path, HttpOnly, etc.
     * 
     * @param response the HTTP response containing Set-Cookie headers
     * @param cookieName the name of the cookie to extract (e.g., "JWT", "JWT_REFRESH_TOKEN")
     * @return the cookie value if found, null otherwise
     */
    protected String extractTokenFromCookies(HttpResponse<String> response, String cookieName) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith(cookieName + "="))
                .map(cookie -> {
                    final String[] parts = cookie.split(";");
                    if (parts.length > 0) {
                        final String cookieValue = parts[0];
                        final int equalIndex = cookieValue.indexOf('=');
                        if (equalIndex > 0 && equalIndex < cookieValue.length() - 1) {
                            return cookieValue.substring(equalIndex + 1);
                        }
                    }
                    return null;
                })
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
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
     * Gets the current JWT token in a thread-safe manner.
     * 
     * @return the current JWT token, or null if not set
     */
    public String getCurrentJwtToken() {
        lock.readLock().lock();
        try {
            return currentJwtToken;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the current refresh token in a thread-safe manner.
     * 
     * @return the current refresh token, or null if not set
     */
    public String getCurrentRefreshToken() {
        lock.readLock().lock();
        try {
            return currentRefreshToken;
        } finally {
            lock.readLock().unlock();
        }
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
     * Updates both JWT and refresh tokens atomically in a thread-safe manner.
     * 
     * <p>This method validates the JWT token format before updating and will not
     * update an invalid JWT token. The refresh token is updated if provided,
     * regardless of format (as refresh tokens may have different formats).
     * 
     * @param jwtToken the new JWT token to set, may be null
     * @param refreshToken the new refresh token to set, may be null
     */
    public void updateTokens(String jwtToken, String refreshToken) {
        lock.writeLock().lock();
        try {
            if (jwtToken != null && isValidJwtToken(jwtToken)) {
                this.currentJwtToken = jwtToken;
                log.trace("JWT token updated");
            }
            if (refreshToken != null) {
                this.currentRefreshToken = refreshToken;
                log.trace("Refresh token updated");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
