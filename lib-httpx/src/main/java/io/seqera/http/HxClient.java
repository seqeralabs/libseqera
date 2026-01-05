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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.seqera.http.auth.AuthenticationCallback;
import io.seqera.http.auth.AuthenticationChallenge;
import io.seqera.http.auth.AuthenticationScheme;
import io.seqera.http.auth.WwwAuthenticateParser;
import io.seqera.util.retry.Retryable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HxClient (Http eXtended Client) - An enhanced HTTP client that wraps Java's {@link HttpClient} with automatic retry logic and authentication support.
 * 
 * <p>This class provides a drop-in replacement for HttpClient that adds enterprise-ready features:
 * <ul>
 *   <li><strong>Automatic Retry</strong>: Configurable retry on HTTP error status codes and network failures</li>
 *   <li><strong>Authentication Support</strong>: Built-in support for JWT Bearer tokens and HTTP Basic authentication</li>
 *   <li><strong>JWT Token Management</strong>: Automatic token refresh on 401 Unauthorized responses</li>
 *   <li><strong>Thread Safety</strong>: Safe for concurrent use across multiple threads</li>
 *   <li><strong>Async Support</strong>: Full support for both synchronous and asynchronous requests</li>
 * </ul>
 * 
 * <p><strong>Retry Behavior:</strong><br>
 * By default, requests are retried on:
 * <ul>
 *   <li>HTTP status codes: 429 (Too Many Requests), 500, 502, 503, 504</li>
 *   <li>Network errors (IOException)</li>
 * </ul>
 * 
 * Retry behavior uses exponential backoff with configurable jitter to prevent thundering herd problems.
 * 
 * <p><strong>JWT Token Refresh:</strong><br>
 * When a request receives a 401 Unauthorized response and both JWT and refresh tokens are configured,
 * the client automatically:
 * <ol>
 *   <li>Attempts to refresh the JWT token using the refresh token</li>
 *   <li>Retries the original request with the new token</li>
 *   <li>Updates internal token state for future requests</li>
 * </ol>
 * 
 * This happens transparently without requiring application code changes.
 * 
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Simple usage with default configuration
 * HxClient client = HxClient.newHxClient();
 * HttpRequest request = HttpRequest.newBuilder()
 *     .uri(URI.create("https://api.example.com/data"))
 *     .GET()
 *     .build();
 * HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
 * 
 * // Equivalent builder approach
 * HxClient client = HxClient.newBuilder().build();
 * 
 * // With JWT token configuration
 * HxClient client = HxClient.newBuilder()
 *     .maxAttempts(3)
 *     .bearerToken("your-jwt-token")
 *     .refreshToken("your-refresh-token")
 *     .refreshTokenUrl("https://api.example.com/oauth/token")
 *     .build();
 * 
 * // With Basic Authentication configuration
 * HxClient client = HxClient.newBuilder()
 *     .basicAuth("username", "password")
 *     .build();
 * 
 * // Configure HTTP client settings
 * HxClient client = HxClient.newBuilder()
 *     .connectTimeout(Duration.ofSeconds(10))
 *     .followRedirects(HttpClient.Redirect.NORMAL)
 *     .bearerToken("your-token")
 *     .build();
 * 
 * // Use existing HttpClient with enhanced functionality
 * HttpClient existingClient = HttpClient.newHttpClient();
 * HxClient client = HxClient.newBuilder()
 *     .httpClient(existingClient)
 *     .bearerToken("token")
 *     .build();
 * 
 * // Async usage
 * CompletableFuture<HttpResponse<String>> future = 
 *     client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
 * }</pre>
 * 
 * <p><strong>Thread Safety:</strong><br>
 * This class is thread-safe and can be shared across multiple threads. Token refresh operations
 * are synchronized to prevent race conditions during concurrent token updates.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see HxConfig
 * @see HxTokenManager
 */
public class HxClient {

    private static final Logger log = LoggerFactory.getLogger(HxClient.class);

    private final HttpClient httpClient;
    private final HxConfig config;
    private final HxTokenManager tokenManager;

    /**
     * Creates a new HxClient with the specified HttpClient and configuration.
     * 
     * <p>This constructor allows full control over both the underlying HTTP client
     * and the retry/authentication behavior. The provided HttpClient is wrapped
     * with enhanced functionality while preserving its original configuration.
     * 
     * <p><strong>Thread Safety:</strong><br>
     * The created HxClient is thread-safe and can be shared across multiple threads.
     * The provided HttpClient should also be thread-safe for concurrent use.
     * 
     * <p><strong>HttpClient Configuration:</strong><br>
     * The HttpClient's configuration (timeouts, redirects, executor) is preserved
     * and used for all requests. HxClient adds retry logic and authentication
     * handling on top of the existing client behavior.
     * 
     * @param httpClient the underlying HttpClient to wrap with retry and authentication functionality.
     *                   Must not be null and should be configured for concurrent use.
     * @param config the configuration for retry behavior, JWT token management, and WWW-Authenticate handling.
     *               If null, the default configuration will be used.
     */
    protected HxClient(HttpClient httpClient, HxConfig config) {
        this(httpClient, config, null);
    }

    protected HxClient(HttpClient httpClient, HxConfig config, HxTokenStore tokenStore) {
        this.httpClient = httpClient;
        this.config = config;
        this.tokenManager = tokenStore != null
                ? new HxTokenManager(config, tokenStore)
                : new HxTokenManager(config);
    }

    /**
     * Creates a new Builder instance for constructing HxClient objects with a fluent API.
     * 
     * <p>The Builder follows the same pattern as Java's {@link HttpClient.Builder}, providing
     * a familiar and consistent API for configuring HTTP clients with enhanced retry and
     * authentication capabilities.
     * 
     * <p><strong>Usage Examples:</strong>
     * <pre>{@code
     * // Basic builder usage with default settings
     * HxClient client = HxClient.newBuilder().build();
     * 
     * // Configure HTTP client settings
     * HxClient client = HxClient.newBuilder()
     *     .connectTimeout(Duration.ofSeconds(10))
     *     .executor(customExecutor)
     *     .followRedirects(HttpClient.Redirect.NORMAL)
     *     .build();
     * 
     * // Configure authentication and retry settings
     * HxClient client = HxClient.newBuilder()
     *     .bearerToken("your-jwt-token")
     *     .refreshToken("your-refresh-token")
     *     .refreshTokenUrl("https://api.example.com/oauth/token")
     *     .maxAttempts(3)
     *     .build();
     * 
     * // Use existing retry configuration
     * var retryConfig = Retryable.ofDefaults().config();
     * HxClient client = HxClient.newBuilder()
     *     .retryConfig(retryConfig)
     *     .bearerToken("token")
     *     .build();
     * 
     * // Use existing HttpClient with enhanced functionality
     * HttpClient existingClient = HttpClient.newHttpClient();
     * HxClient client = HxClient.newBuilder()
     *     .httpClient(existingClient)
     *     .bearerToken("token")
     *     .build();
     * }</pre>
     * 
     * @return a new Builder instance with default settings
     * @see Builder
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Creates a new HxClient instance with default settings.
     * 
     * <p>This is a convenience method equivalent to {@code HxClient.newBuilder().build()}.
     * It creates an HxClient with:
     * <ul>
     *   <li>Default HttpClient configuration</li>
     *   <li>Default retry settings (5 attempts, 500ms initial delay, exponential backoff)</li>
     *   <li>Default retry status codes (429, 500, 502, 503, 504)</li>
     *   <li>No authentication configured</li>
     * </ul>
     * 
     * <p>For custom configuration, use {@link #newBuilder()} instead.
     * 
     * <p><strong>Usage Examples:</strong>
     * <pre>{@code
     * // Simple usage with defaults
     * HxClient client = HxClient.newHxClient();
     * 
     * // Equivalent to:
     * HxClient client = HxClient.newBuilder().build();
     * }</pre>
     * 
     * @return a new HxClient instance with default configuration
     * @see #newBuilder()
     */
    public static HxClient newHxClient() {
        return newBuilder().build();
    }

    /**
     * Sends an HTTP request synchronously with automatic retry logic and JWT token refresh.
     * 
     * <p>This method will automatically:
     * <ul>
     *   <li>Add JWT authentication header if configured</li>
     *   <li>Retry on configured HTTP status codes (default: 429, 500, 502, 503, 504)</li>
     *   <li>Retry on IOException (network errors)</li>
     *   <li>Attempt token refresh on 401 Unauthorized responses</li>
     * </ul>
     * 
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param responseBodyHandler the response body handler
     * @return the HTTP response
     * @throws IOException if all retry attempts fail
     * @throws InterruptedException if the operation is interrupted
     */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) 
            throws IOException, InterruptedException {
        return sendWithRetry(request, responseBodyHandler);
    }

    public HttpResponse<String> sendAsString(HttpRequest request) {
        return sendWithRetry(request, HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<byte[]> sendAsBytes(HttpRequest request) {
        return sendWithRetry(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    public HttpResponse<InputStream> sendAsStream(HttpRequest request) {
        return sendWithRetry(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // ========================================================================
    // Multi-user authentication methods
    // ========================================================================

    /**
     * Sends an HTTP request synchronously with automatic retry logic using the specified authentication.
     *
     * <p>This method allows per-request authentication for multi-user scenarios. The token
     * is retrieved from the token store (and may have been refreshed since initial creation).
     * If a 401 response is received, the token will be refreshed and the request retried.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param auth the authentication data for this request
     * @param responseBodyHandler the response body handler
     * @return the HTTP response
     * @throws IOException if all retry attempts fail
     * @throws InterruptedException if the operation is interrupted
     */
    public <T> HttpResponse<T> send(HttpRequest request, HxAuth auth, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException, InterruptedException {
        return sendWithRetry(request, auth, responseBodyHandler);
    }

    /**
     * Sends an HTTP request synchronously as String using the specified authentication.
     *
     * @param request the HTTP request to send
     * @param auth the authentication data for this request
     * @return the HTTP response with String body
     */
    public HttpResponse<String> sendAsString(HttpRequest request, HxAuth auth) {
        return sendWithRetry(request, auth, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends an HTTP request synchronously as byte array using the specified authentication.
     *
     * @param request the HTTP request to send
     * @param auth the authentication data for this request
     * @return the HTTP response with byte array body
     */
    public HttpResponse<byte[]> sendAsBytes(HttpRequest request, HxAuth auth) {
        return sendWithRetry(request, auth, HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * Sends an HTTP request synchronously as InputStream using the specified authentication.
     *
     * @param request the HTTP request to send
     * @param auth the authentication data for this request
     * @return the HTTP response with InputStream body
     */
    public HttpResponse<InputStream> sendAsStream(HttpRequest request, HxAuth auth) {
        return sendWithRetry(request, auth, HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * Sends an HTTP request asynchronously using the specified authentication.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param auth the authentication data for this request
     * @param responseBodyHandler the response body handler
     * @return a CompletableFuture that will complete with the HTTP response
     */
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HxAuth auth,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        Executor executor = httpClient.executor().orElse(java.util.concurrent.ForkJoinPool.commonPool());
        return sendWithRetryAsync(request, auth, responseBodyHandler, executor);
    }

    /**
     * Sends an HTTP request asynchronously using the specified authentication and executor.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param auth the authentication data for this request
     * @param responseBodyHandler the response body handler
     * @param executor the executor to use for async operations
     * @return a CompletableFuture that will complete with the HTTP response
     */
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HxAuth auth,
            HttpResponse.BodyHandler<T> responseBodyHandler, Executor executor) {
        return sendWithRetryAsync(request, auth, responseBodyHandler, executor);
    }

    /**
     * Sends an HTTP request asynchronously with automatic retry logic and JWT token refresh.
     * 
     * <p>This method will automatically:
     * <ul>
     *   <li>Add JWT authentication header if configured</li>
     *   <li>Retry on configured HTTP status codes (default: 429, 500, 502, 503, 504)</li>
     *   <li>Retry on IOException (network errors)</li>
     *   <li>Attempt token refresh on 401 Unauthorized responses</li>
     * </ul>
     * 
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param responseBodyHandler the response body handler
     * @return a CompletableFuture that will complete with the HTTP response
     */
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        // Use HttpClient's executor or ForkJoinPool.commonPool() as fallback
        Executor executor = httpClient.executor().orElse(java.util.concurrent.ForkJoinPool.commonPool());
        return sendWithRetryAsync(request, responseBodyHandler, executor);
    }

    /**
     * Sends an HTTP request asynchronously with automatic retry logic and JWT token refresh,
     * using the specified executor for async operations.
     * 
     * <p>This method will automatically:
     * <ul>
     *   <li>Add JWT authentication header if configured</li>
     *   <li>Retry on configured HTTP status codes (default: 429, 500, 502, 503, 504)</li>
     *   <li>Retry on IOException (network errors)</li>
     *   <li>Attempt token refresh on 401 Unauthorized responses</li>
     * </ul>
     * 
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param responseBodyHandler the response body handler
     * @param executor the executor to use for async operations
     * @return a CompletableFuture that will complete with the HTTP response
     */
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, 
            HttpResponse.BodyHandler<T> responseBodyHandler, Executor executor) {
        return sendWithRetryAsync(request, responseBodyHandler, executor);
    }

    /**
     * Internal method that implements the retry logic for synchronous requests.
     *
     * <p>Delegates to {@link #sendWithRetry(HttpRequest, HxAuth, HttpResponse.BodyHandler)} with default auth.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param responseBodyHandler the response body handler
     * @return the HTTP response after successful execution or retry exhaustion
     */
    protected <T> HttpResponse<T> sendWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
        return sendWithRetry(request, tokenManager.getDefaultAuth(), responseBodyHandler);
    }

    /**
     * Internal method that implements the retry logic for asynchronous requests.
     *
     * <p>Delegates to {@link #sendWithRetryAsync(HttpRequest, HxAuth, HttpResponse.BodyHandler, Executor)} with default auth.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param responseBodyHandler the response body handler
     * @param executor optional executor for async operations, may be null
     * @return a CompletableFuture that completes with the HTTP response
     */
    protected <T> CompletableFuture<HttpResponse<T>> sendWithRetryAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            Executor executor) {
        return sendWithRetryAsync(request, tokenManager.getDefaultAuth(), responseBodyHandler, executor);
    }

    /**
     * Internal method that implements the retry logic for synchronous requests.
     *
     * <p>This method handles the core retry functionality including:
     * <ul>
     *   <li>Adding authentication headers</li>
     *   <li>Executing retry policy for network errors and specific HTTP status codes</li>
     *   <li>Attempting token refresh on 401 responses</li>
     *   <li>Re-executing requests with refreshed tokens</li>
     * </ul>
     *
     * <p>If auth is null, uses the default token from configuration. Otherwise uses
     * the specific {@link HxAuth} for multi-user authentication scenarios.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param auth the authentication data for this request, or null to use default token
     * @param responseBodyHandler the response body handler
     * @return the HTTP response after successful execution or retry exhaustion
     */
    protected <T> HttpResponse<T> sendWithRetry(HttpRequest request, HxAuth auth, HttpResponse.BodyHandler<T> responseBodyHandler) {
        final boolean[] tokenRefreshed = {false};

        final Retryable<HttpResponse<T>> retry = Retryable.<HttpResponse<T>>of(config)
                .retryCondition(config.getRetryCondition())
                .retryIf(this::shouldRetryOnResponse)
                .onRetry(event -> {
                    String message = event.getFailure() != null ? event.getFailure().getMessage()
                            : String.valueOf(event.getResult().statusCode());
                    log.debug("HTTP request retry attempt {}: {}", event.getAttempt(), message);
                });

        return retry.apply(() -> {
            final HttpRequest actualRequest = (auth != null)
                    ? tokenManager.addAuthHeader(request, auth)
                    : tokenManager.addAuthHeader(request);
            final HttpResponse<T> response = httpClient.send(actualRequest, responseBodyHandler);

            if (response.statusCode() != 401 || tokenRefreshed[0]) {
                return response;
            }

            // Try JWT token refresh
            final boolean canRefresh = (auth != null) ? tokenManager.canRefreshToken(auth) : tokenManager.canRefreshToken();
            if (canRefresh) {
                final String debugKey = HxAuth.keyOrDefault(auth, "-");
                log.debug("Received 401 status for auth key {}, attempting token refresh", debugKey);
                try {
                    final boolean refreshed = (auth != null)
                            ? tokenManager.getOrRefreshTokenAsync(auth).get() != null
                            : tokenManager.getOrRefreshTokenAsync().get();
                    if (refreshed) {
                        tokenRefreshed[0] = true;
                        final HttpRequest refreshedRequest = (auth != null)
                                ? tokenManager.addAuthHeader(request, auth)
                                : tokenManager.addAuthHeader(request);
                        closeResponse(response);
                        return httpClient.send(refreshedRequest, responseBodyHandler);
                    }
                } catch (Exception e) {
                    log.warn("Token refresh failed for auth key {}: {}", debugKey, e.getMessage());
                }
            }

            // Try WWW-Authenticate challenge handling if enabled (only for default auth)
            if (auth == null && config.isWwwAuthenticateEnabled()) {
                HttpRequest authenticatedRequest = handleWwwAuthenticate(request, response);
                if (authenticatedRequest != null) {
                    log.debug("Retrying request with WWW-Authenticate challenge");
                    closeResponse(response);
                    return httpClient.send(authenticatedRequest, responseBodyHandler);
                }
            }

            return response;
        });
    }

    /**
     * Internal method that implements the retry logic for asynchronous requests.
     *
     * <p>This method handles the core async retry functionality including:
     * <ul>
     *   <li>Adding authentication headers</li>
     *   <li>Executing retry policy for network errors and specific HTTP status codes</li>
     *   <li>Attempting token refresh on 401 responses</li>
     *   <li>Re-executing requests with refreshed tokens</li>
     * </ul>
     *
     * <p>If auth is null, uses the default token from configuration. Otherwise uses
     * the specific {@link HxAuth} for multi-user authentication scenarios.
     *
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param auth the authentication data for this request, or null to use default token
     * @param responseBodyHandler the response body handler
     * @param executor optional executor for async operations, may be null
     * @return a CompletableFuture that completes with the HTTP response
     */
    protected <T> CompletableFuture<HttpResponse<T>> sendWithRetryAsync(
            HttpRequest request,
            HxAuth auth,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            Executor executor) {

        final boolean[] tokenRefreshed = {false};

        final Retryable<HttpResponse<T>> retry = Retryable.<HttpResponse<T>>of(config)
                .retryCondition(config.getRetryCondition())
                .retryIf(this::shouldRetryOnResponse)
                .onRetry(event -> {
                    String message = event.getFailure() != null ? event.getFailure().getMessage()
                            : String.valueOf(event.getResult().statusCode());
                    log.debug("HTTP async request retry attempt {}: {}", event.getAttempt(), message);
                });

        return retry.applyAsync(() -> {
            final HttpRequest actualRequest = (auth != null)
                    ? tokenManager.addAuthHeader(request, auth)
                    : tokenManager.addAuthHeader(request);

            try {
                final HttpResponse<T> response = httpClient.sendAsync(actualRequest, responseBodyHandler).get();

                if (response.statusCode() != 401 || tokenRefreshed[0]) {
                    return response;
                }

                // Try JWT token refresh
                final boolean canRefresh = (auth != null) ? tokenManager.canRefreshToken(auth) : tokenManager.canRefreshToken();
                if (canRefresh) {
                    final String debugKey = HxAuth.keyOrDefault(auth, "-");
                    log.debug("Received 401 status in async call for auth key {}, attempting token refresh", debugKey);
                    try {
                        final boolean refreshed = (auth != null)
                                ? tokenManager.getOrRefreshTokenAsync(auth).get() != null
                                : tokenManager.getOrRefreshTokenAsync().get();
                        if (refreshed) {
                            tokenRefreshed[0] = true;
                            final HttpRequest refreshedRequest = (auth != null)
                                    ? tokenManager.addAuthHeader(request, auth)
                                    : tokenManager.addAuthHeader(request);
                            closeResponse(response);
                            return httpClient.sendAsync(refreshedRequest, responseBodyHandler).get();
                        }
                    } catch (Exception e) {
                        log.warn("Async token refresh failed for auth key {}: {}", debugKey, e.getMessage());
                    }
                }

                // Try WWW-Authenticate challenge handling if enabled (only for default auth)
                if (auth == null && config.isWwwAuthenticateEnabled()) {
                    HttpRequest authenticatedRequest = handleWwwAuthenticate(request, response);
                    if (authenticatedRequest != null) {
                        log.debug("Retrying async request with WWW-Authenticate credentials");
                        closeResponse(response);
                        return httpClient.sendAsync(authenticatedRequest, responseBodyHandler).get();
                    }
                }

                return response;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                } else if (e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during async request", e.getCause());
                } else if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                } else {
                    throw new IOException("Async request failed", e.getCause());
                }
            }
        }, executor);
    }

    /**
     * Determines whether to retry a request based on the exception that occurred.
     * 
     * <p>By default, retries on IOException which typically indicates network-level issues
     * such as connection timeouts, connection refused, etc.
     * 
     * @param throwable the exception that occurred during the request
     * @return true if the request should be retried, false otherwise
     */
    protected boolean shouldRetryOnException(Throwable throwable) {
        if (throwable instanceof IOException) {
            log.debug("Retrying on IOException: {}", throwable.getMessage());
            return true;
        }
        return false;
    }

    /**
     * Determines whether to retry a request based on the HTTP response status code.
     * 
     * <p>Uses the configured set of retry status codes (default: 429, 500, 502, 503, 504).
     * These codes typically indicate temporary server issues or rate limiting.
     * 
     * @param <T> the response body type
     * @param response the HTTP response to evaluate
     * @return true if the request should be retried based on status code, false otherwise
     */
    protected <T> boolean shouldRetryOnResponse(HttpResponse<T> response) {
        if (config.getRetryStatusCodes().contains(response.statusCode())) {
            log.debug("Retrying on HTTP status code: {}", response.statusCode());
            return true;
        }
        return false;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public HxConfig getConfig() {
        return config;
    }

    public HxTokenManager getTokenManager() {
        return tokenManager;
    }

    /**
     * Handles WWW-Authenticate challenges from a 401 response by parsing challenges and
     * attempting to provide appropriate authentication credentials.
     * 
     * <p>This method implements the complete WWW-Authenticate challenge handling flow:
     * <ol>
     *   <li>Extracts all WWW-Authenticate headers from the 401 response</li>
     *   <li>Parses challenges for supported schemes (Basic, Bearer only)</li>
     *   <li>For each challenge, attempts to get credentials from the configured callback</li>
     *   <li>Falls back to anonymous authentication if no credentials are provided</li>
     *   <li>Returns a new request with the first successful Authorization header</li>
     * </ol>
     * 
     * <p><strong>Credential Fallback Chain:</strong>
     * <ol>
     *   <li><strong>Callback credentials</strong>: Uses {@link AuthenticationCallback} if configured</li>
     *   <li><strong>Anonymous authentication</strong>: Attempts to get anonymous credentials</li>
     *   <li><strong>Failure</strong>: Returns null if no authentication method succeeds</li>
     * </ol>
     * 
     * <p><strong>Anonymous Authentication Behavior:</strong>
     * <ul>
     *   <li><strong>Basic</strong>: Uses empty credentials (base64 encoded ":")</li>
     *   <li><strong>Bearer</strong>: Attempts OAuth2 anonymous token retrieval from realm URL</li>
     * </ul>
     * 
     * <p><strong>Error Handling:</strong><br>
     * If the authentication callback throws an exception, it's logged as a warning
     * and the method continues with anonymous authentication fallback.
     * 
     * @param originalRequest the original HTTP request that received 401. Must not be null.
     * @param response the 401 HTTP response containing WWW-Authenticate headers. Must not be null.
     * @return a new HttpRequest with Authorization header added, or null if no suitable
     *         authentication method could be found or if all authentication attempts failed
     */
    protected HttpRequest handleWwwAuthenticate(HttpRequest originalRequest, HttpResponse<?> response) {
        List<String> wwwAuthHeaders = response.headers().allValues("WWW-Authenticate");
        if (wwwAuthHeaders.isEmpty()) {
            log.debug("No WWW-Authenticate headers found in 401 response");
            return null;
        }

        for (String headerValue : wwwAuthHeaders) {
            List<AuthenticationChallenge> challenges = WwwAuthenticateParser.parse(headerValue);
            
            for (AuthenticationChallenge challenge : challenges) {
                HttpRequest authenticatedRequest = createAuthenticatedRequest(originalRequest, challenge);
                if (authenticatedRequest != null) {
                    return authenticatedRequest;
                }
            }
        }

        log.debug("No suitable authentication method found for WWW-Authenticate challenges");
        return null;
    }

    /**
     * Creates an authenticated request for a specific challenge by obtaining credentials
     * and adding the appropriate Authorization header.
     * 
     * @param originalRequest the original HTTP request
     * @param challenge the authentication challenge to handle
     * @return a new HttpRequest with Authorization header, or null if credentials are not available
     */
    protected HttpRequest createAuthenticatedRequest(HttpRequest originalRequest, AuthenticationChallenge challenge) {
        String credentials = null;
        
        // Try to get credentials from callback if available
        AuthenticationCallback callback = config.getAuthenticationCallback();
        if (callback != null) {
            try {
                credentials = callback.getCredentials(challenge.getScheme(), challenge.getRealm());
            } catch (Exception e) {
                log.warn("Authentication callback failed for scheme {} realm {}: {}", 
                        challenge.getScheme(), challenge.getRealm(), e.getMessage());
            }
        }
        
        // Fall back to anonymous authentication if no credentials provided
        if (credentials == null) {
            credentials = getAnonymousCredentials(challenge.getScheme(), challenge);
        }
        
        if (credentials != null) {
            String authorizationHeader = createAuthorizationHeader(challenge.getScheme(), credentials);
            if (authorizationHeader != null) {
                log.debug("Creating authenticated request for scheme {} realm {}", 
                        challenge.getScheme(), challenge.getRealm());
                return HttpRequest.newBuilder(originalRequest, (name, value) -> true)
                        .header("Authorization", authorizationHeader)
                        .build();
            }
        }
        
        return null;
    }

    /**
     * Gets anonymous credentials for the specified authentication scheme.
     * 
     * @param scheme the authentication scheme
     * @param challenge the authentication challenge containing parameters like realm, service, scope
     * @return anonymous credentials, or null if not supported
     */
    protected String getAnonymousCredentials(AuthenticationScheme scheme, AuthenticationChallenge challenge) {
        switch (scheme) {
            case BASIC:
                // Anonymous basic auth with empty credentials
                return Base64.getEncoder().encodeToString(":".getBytes());
            case BEARER:
                // For Bearer tokens, attempt to get anonymous token from auth endpoint
                return getAnonymousBearerToken(challenge);
            default:
                return null;
        }
    }

    /**
     * Attempts to get an anonymous Bearer token from the OAuth2 authentication endpoint.
     * 
     * <p>This method implements the OAuth2 anonymous token flow by making a request
     * to the realm URL (token endpoint) with service and scope parameters from the
     * authentication challenge. This is commonly used by container registries to
     * provide anonymous read access to public repositories.
     * 
     * <p><strong>Token Request Process:</strong>
     * <ol>
     *   <li>Constructs token URL from challenge realm parameter</li>
     *   <li>Adds service and scope query parameters if present in challenge</li>
     *   <li>Makes HTTP GET request to token endpoint</li>
     *   <li>Parses JSON response to extract token or access_token field</li>
     * </ol>
     * 
     * <p><strong>Expected Token Response Format:</strong>
     * <pre>
     * {
     *   "token": "anonymous-token-value",
     *   "expires_in": 300,
     *   "issued_at": "2023-01-01T00:00:00Z"
     * }
     * </pre>
     * or
     * <pre>
     * {
     *   "access_token": "anonymous-token-value",
     *   "token_type": "Bearer"
     * }
     * </pre>
     * 
     * <p><strong>Error Handling:</strong><br>
     * Network failures, non-200 responses, and JSON parsing errors are logged
     * and result in null being returned. This method never throws exceptions.
     * 
     * @param challenge the Bearer authentication challenge containing realm, service, and scope parameters.
     *                 The realm parameter is required; service and scope are optional.
     * @return the anonymous bearer token value (without "Bearer " prefix), or null if the token
     *         could not be retrieved due to network errors, invalid responses, or missing realm
     */
    protected String getAnonymousBearerToken(AuthenticationChallenge challenge) {
        String realm = challenge.getRealm();
        String service = challenge.getParameter("service");
        String scope = challenge.getParameter("scope");
        
        if (realm == null) {
            log.debug("No realm specified in Bearer challenge, cannot get anonymous token");
            return null;
        }
        
        try {
            StringBuilder tokenUrl = new StringBuilder(realm);
            tokenUrl.append("?");
            
            if (service != null) {
                tokenUrl.append("service=").append(java.net.URLEncoder.encode(service, "UTF-8")).append("&");
            }
            if (scope != null) {
                tokenUrl.append("scope=").append(java.net.URLEncoder.encode(scope, "UTF-8"));
            }
            
            log.debug("Requesting anonymous Bearer token from: {}", tokenUrl);
            
            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(tokenUrl.toString()))
                    .GET()
                    .timeout(config.getTokenRefreshTimeout())
                    .build();
            
            HttpClient tokenClient = HttpClient.newHttpClient();
            HttpResponse<String> tokenResponse = tokenClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            
            if (tokenResponse.statusCode() == 200) {
                // Parse JSON response to extract token
                try {
                    JsonObject jsonResponse = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();
                    if (jsonResponse.has("token")) {
                        String token = jsonResponse.get("token").getAsString();
                        log.debug("Successfully obtained anonymous Bearer token");
                        return token;
                    } else if (jsonResponse.has("access_token")) {
                        String token = jsonResponse.get("access_token").getAsString();
                        log.debug("Successfully obtained anonymous Bearer access_token");
                        return token;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse token response JSON: {}", e.getMessage());
                }
            } else {
                log.debug("Token endpoint returned status {}: {}", tokenResponse.statusCode(), tokenResponse.body());
            }
        } catch (Exception e) {
            log.warn("Failed to get anonymous Bearer token: {}", e.getMessage());
        }
        
        return null;
    }

    /**
     * Creates the Authorization header value for the given scheme and credentials.
     * 
     * @param scheme the authentication scheme
     * @param credentials the credentials (already encoded if necessary)
     * @return the Authorization header value, or null if scheme is not supported
     */
    protected String createAuthorizationHeader(AuthenticationScheme scheme, String credentials) {
        switch (scheme) {
            case BASIC:
                return "Basic " + credentials;
            case BEARER:
                return "Bearer " + credentials;
            default:
                log.warn("Unsupported authentication scheme for header creation: {}", scheme);
                return null;
        }
    }

    /**
     * Returns the currently active JWT token, if any.
     * 
     * <p>This method provides access to the JWT token that is currently being used
     * for authentication headers in HTTP requests. The token may be null if:
     * <ul>
     *   <li>No JWT token was initially configured</li>
     *   <li>Token refresh failed and no valid token is available</li>
     *   <li>The token has expired and automatic refresh is disabled</li>
     * </ul>
     * 
     * <p><strong>Token Management:</strong><br>
     * The returned token represents the current state and may change over time
     * due to automatic token refresh operations. For thread-safety, this method
     * returns a snapshot of the current token value.
     * 
     * @return the current JWT token string, or null if no token is available
     * @see #getCurrentRefreshToken()
     * @see HxTokenManager#getCurrentJwtToken()
     */
    public String getCurrentJwtToken() {
        return tokenManager.getCurrentJwtToken();
    }

    /**
     * Returns the currently active refresh token, if any.
     * 
     * <p>This method provides access to the refresh token that is used for
     * automatic JWT token renewal when a 401 Unauthorized response is received.
     * The refresh token may be null if:
     * <ul>
     *   <li>No refresh token was initially configured</li>
     *   <li>The refresh token has been revoked or expired</li>
     *   <li>Token refresh functionality is not enabled</li>
     * </ul>
     * 
     * <p><strong>Security Note:</strong><br>
     * Refresh tokens are sensitive credentials that should be handled with care.
     * Avoid logging or exposing refresh tokens in client-side code or unsecured
     * storage locations.
     * 
     * @return the current refresh token string, or null if no refresh token is available
     * @see #getCurrentJwtToken()
     * @see HxTokenManager#getCurrentRefreshToken()
     */
    public String getCurrentRefreshToken() {
        return tokenManager.getCurrentRefreshToken();
    }

    /**
     * Safely closes an HTTP response to prevent resource leaks.
     * 
     * <p>This utility method properly disposes of HTTP response resources by closing
     * the response body if it implements {@link Closeable}. This is particularly
     * important for streaming response bodies and helps prevent memory leaks.
     * 
     * <p><strong>Background:</strong><br>
     * Java's HttpClient can leave response bodies unclosed in certain scenarios,
     * leading to resource leaks. This method addresses the issue described in
     * <a href="https://bugs.openjdk.org/browse/JDK-8308364">JDK-8308364</a>.
     * 
     * <p><strong>Error Handling:</strong><br>
     * Any exceptions during the close operation are caught and logged at debug level
     * to prevent disrupting the main request flow. This method never throws exceptions.
     * 
     * <p><strong>Thread Safety:</strong><br>
     * This method is thread-safe and can be called concurrently on different
     * response instances.
     * 
     * @param response the HTTP response to close, may be null (no-op if null)
     */
    static void closeResponse(HttpResponse<?> response) {
        try {
            // close the httpclient response to prevent leaks
            // https://bugs.openjdk.org/browse/JDK-8308364
            final var b0 = response.body();
            if( b0 instanceof Closeable)
                ((Closeable)b0).close();
        }
        catch (Throwable e) {
            log.debug("Unexpected error while closing http response - cause: {}", e.getMessage());
        }
    }

    /**
     * Builder class for constructing HxClient instances with a fluent API.
     * 
     * <p>The Builder provides a familiar interface similar to {@link HttpClient.Builder},
     * allowing configuration of both the underlying HTTP client and HxClient-specific
     * features like retry logic and authentication.
     * 
     * <p>All builder methods return the builder instance to enable method chaining.
     * The {@link #build()} method creates the final immutable HxClient instance.
     * 
     * <p><strong>Thread Safety:</strong><br>
     * Builder instances are not thread-safe and should not be used concurrently
     * from multiple threads without external synchronization.
     */
    public static class Builder {
        private HttpClient.Builder httpClientBuilder;
        private HttpClient httpClient;
        private HxConfig.Builder configBuilder;
        private HxTokenStore tokenStore;

        /**
         * Creates a new Builder with default settings.
         */
        public Builder() {
            this.httpClientBuilder = HttpClient.newBuilder();
            this.configBuilder = HxConfig.newBuilder();
        }
        
        /**
         * Sets the underlying HttpClient to use.
         * 
         * <p>If this method is called, the HttpClient configuration methods
         * ({@link #connectTimeout}, {@link #executor}, etc.) will be ignored
         * as the provided HttpClient will be used directly.
         * 
         * @param httpClient the HttpClient to use
         * @return this Builder instance
         * @throws NullPointerException if httpClient is null
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient cannot be null");
            return this;
        }
        
        /**
         * Sets the HxConfig to use directly.
         * 
         * <p>If this method is called, HxConfig-specific builder methods
         * will be ignored as the provided configuration will be used directly.
         * 
         * @param config the HxConfig to use
         * @return this Builder instance
         * @throws NullPointerException if config is null
         */
        public Builder config(HxConfig config) {
            this.configBuilder = HxConfig.newBuilder()
                    .retryConfig(config)
                    .bearerToken(config.getJwtToken())
                    .refreshToken(config.getRefreshToken())
                    .refreshTokenUrl(config.getRefreshTokenUrl())
                    .basicAuth(config.getBasicAuthToken())
                    .retryStatusCodes(config.getRetryStatusCodes())
                    .wwwAuthentication(config.isWwwAuthenticateEnabled())
                    .wwwAuthenticationCallback(config.getAuthenticationCallback());
            return this;
        }
        
        // HttpClient.Builder methods
        
        /**
         * Sets the connect timeout duration for this client.
         * 
         * @param timeout the connect timeout duration
         * @return this Builder instance
         * @see HttpClient.Builder#connectTimeout(Duration)
         */
        public Builder connectTimeout(Duration timeout) {
            this.httpClientBuilder.connectTimeout(timeout);
            return this;
        }
        
        /**
         * Sets the executor to be used for asynchronous and dependent tasks.
         * 
         * @param executor the executor for async operations
         * @return this Builder instance
         * @see HttpClient.Builder#executor(Executor)
         */
        public Builder executor(Executor executor) {
            this.httpClientBuilder.executor(executor);
            return this;
        }
        
        /**
         * Specifies whether requests will automatically follow redirects.
         * 
         * @param policy the redirect policy
         * @return this Builder instance
         * @see HttpClient.Builder#followRedirects(HttpClient.Redirect)
         */
        public Builder followRedirects(HttpClient.Redirect policy) {
            this.httpClientBuilder.followRedirects(policy);
            return this;
        }
        
        /**
         * Requests a specific HTTP protocol version where possible.
         * 
         * @param version the HTTP version to prefer
         * @return this Builder instance
         * @see HttpClient.Builder#version(HttpClient.Version)
         */
        public Builder version(HttpClient.Version version) {
            this.httpClientBuilder.version(version);
            return this;
        }
        
        // HxConfig convenience methods
        
        /**
         * Sets the maximum number of retry attempts.
         * 
         * @param attempts maximum retry attempts (must be positive)
         * @return this Builder instance
         */
        public Builder maxAttempts(int attempts) {
            this.configBuilder.maxAttempts(attempts);
            return this;
        }
        
        /**
         * Sets the initial retry delay duration.
         * 
         * @param delay the initial delay between retries
         * @return this Builder instance
         */
        public Builder retryDelay(Duration delay) {
            this.configBuilder.delay(delay);
            return this;
        }
        
        /**
         * Sets the retry configuration from a generic Retryable.Config.
         * 
         * <p>This allows configuring all retry parameters at once from an existing
         * Retryable.Config instance. This is useful for integrating with existing
         * retry configurations or when you want to configure multiple retry parameters
         * with a single method call.
         * 
         * <p>The following retry parameters will be copied:
         * <ul>
         *   <li>Initial delay</li>
         *   <li>Maximum delay</li>
         *   <li>Maximum attempts</li>
         *   <li>Jitter factor</li>
         *   <li>Backoff multiplier</li>
         * </ul>
         * 
         * <p><strong>Usage Examples:</strong>
         * <pre>{@code
         * // Using existing Retryable configuration
         * var retryConfig = Retryable.ofDefaults().config();
         * HxClient client = HxClient.newBuilder()
         *     .retryConfig(retryConfig)
         *     .bearerToken("token")
         *     .build();
         * 
         * // Equivalent to setting individual parameters
         * HxClient client = HxClient.newBuilder()
         *     .maxAttempts(retryConfig.getMaxAttempts())
         *     .retryDelay(retryConfig.getDelayAsDuration())
         *     .bearerToken("token")
         *     .build();
         * }</pre>
         * 
         * @param retryConfig the retry configuration to use. If null, no changes are made.
         * @return this Builder instance
         */
        public Builder retryConfig(Retryable.Config retryConfig) {
            if (retryConfig != null) {
                this.configBuilder.retryConfig(retryConfig);
            }
            return this;
        }
        
        /**
         * Sets the Bearer/JWT token for authentication.
         * 
         * @param token the Bearer token to use for authentication
         * @return this Builder instance
         */
        public Builder bearerToken(String token) {
            this.configBuilder.bearerToken(token);
            return this;
        }
        
        /**
         * Sets the refresh token for automatic JWT token renewal.
         * 
         * @param token the refresh token
         * @return this Builder instance
         */
        public Builder refreshToken(String token) {
            this.configBuilder.refreshToken(token);
            return this;
        }
        
        /**
         * Sets the URL endpoint for refreshing JWT tokens.
         * 
         * @param url the token refresh endpoint URL
         * @return this Builder instance
         */
        public Builder refreshTokenUrl(String url) {
            this.configBuilder.refreshTokenUrl(url);
            return this;
        }
        
        /**
         * Sets basic authentication credentials.
         * 
         * @param username the username for basic auth
         * @param password the password for basic auth
         * @return this Builder instance
         */
        public Builder basicAuth(String username, String password) {
            this.configBuilder.basicAuth(username, password);
            return this;
        }

        /**
         * Sets basic authentication using a pre-formatted token.
         * 
         * <p>This method accepts a token that is already in the "username:password" format
         * expected by HTTP Basic authentication. The token will be Base64-encoded automatically
         * when creating the Authorization header.
         * 
         * <p><strong>Token Format:</strong><br>
         * The token must be in the format "username:password". For example:
         * <pre>{@code
         * client = HxClient.newBuilder()
         *     .basicAuth("myuser:mypassword")
         *     .build();
         * }</pre>
         * 
         * <p><strong>Security Considerations:</strong>
         * <ul>
         *   <li>Basic authentication sends credentials in every request</li>
         *   <li>Always use HTTPS when using basic authentication</li>
         *   <li>Store credentials securely and avoid hardcoding in source code</li>
         *   <li>Consider using Bearer tokens for better security when possible</li>
         * </ul>
         * 
         * <p><strong>Authentication Priority:</strong><br>
         * If both JWT tokens and basic authentication are configured, JWT authentication
         * takes precedence. Basic authentication will only be used if no JWT token is available.
         * 
         * <p><strong>Alternative:</strong><br>
         * For separate username and password parameters, use {@link #basicAuth(String, String)} instead.
         * 
         * @param token the basic auth token in "username:password" format
         * @return this Builder instance
         * @see #basicAuth(String, String)
         */
        public Builder basicAuth(String token) {
            this.configBuilder.basicAuth(token);
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
         * <p><strong>Usage Example:</strong>
         * <pre>{@code
         * HxClient client = HxClient.newBuilder()
         *     .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
         *     .bearerToken("your-jwt-token")
         *     .build();
         * }</pre>
         * 
         * @param policy the cookie policy for refresh token operations, or null for default behavior
         * @return this Builder instance
         */
        public Builder refreshCookiePolicy(CookiePolicy policy) {
            this.configBuilder.refreshCookiePolicy(policy);
            return this;
        }

        /**
         * Sets the token store for multi-session authentication.
         *
         * <p>The token store manages JWT token pairs for multiple authentication sessions.
         * By default, an in-memory {@code ConcurrentHashMap}-based store is used. For distributed
         * deployments, provide a custom implementation backed by Redis, a database, or other
         * distributed cache.
         *
         * <p><strong>Usage Example:</strong>
         * <pre>{@code
         * HxTokenStore redisStore = new RedisTokenStore();
         * HxClient client = HxClient.newBuilder()
         *     .tokenStore(redisStore)
         *     .refreshTokenUrl("https://api.example.com/oauth/token")
         *     .build();
         * }</pre>
         *
         * @param tokenStore the token store implementation
         * @return this Builder instance
         * @see HxTokenStore
         */
        public Builder tokenStore(HxTokenStore tokenStore) {
            this.tokenStore = tokenStore;
            return this;
        }

        /**
         * Builds and returns a new HxClient instance.
         * 
         * <p>This method creates the final HxClient using either the provided
         * HttpClient or building one from the configured HttpClient.Builder settings.
         * 
         * @return a new HxClient instance
         */
        public HxClient build() {
            final HttpClient actualHttpClient = (httpClient != null)
                    ? httpClient
                    : httpClientBuilder.build();
            final HxConfig actualConfig = configBuilder.build();
            return new HxClient(actualHttpClient, actualConfig, tokenStore);
        }
    }
}
