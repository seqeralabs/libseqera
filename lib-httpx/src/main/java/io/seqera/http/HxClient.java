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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * HxClient (Http eXtended Client) - An enhanced HTTP client that wraps Java's {@link HttpClient} with automatic retry logic and JWT token refresh.
 * 
 * <p>This class provides a drop-in replacement for HttpClient that adds enterprise-ready features:
 * <ul>
 *   <li><strong>Automatic Retry</strong>: Configurable retry on HTTP error status codes and network failures</li>
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
 * // Basic usage with default configuration
 * HxClient client = HxClient.create();
 * HttpRequest request = HttpRequest.newBuilder()
 *     .uri(URI.create("https://api.example.com/data"))
 *     .GET()
 *     .build();
 * HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
 * 
 * // With custom configuration
 * HttpConfig config = HttpConfig.builder()
 *     .withMaxAttempts(3)
 *     .withJwtToken("your-jwt-token")
 *     .withRefreshToken("your-refresh-token")
 *     .withRefreshTokenUrl("https://api.example.com/oauth/token")
 *     .build();
 * HxClient client = HxClient.create(config);
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
     *               If null, default configuration will be used.
     */
    public HxClient(HttpClient httpClient, HxConfig config) {
        this.httpClient = httpClient;
        this.config = config;
        this.tokenManager = new HxTokenManager(config);
    }

    /**
     * Creates a new HxClient with a default HttpClient and the specified configuration.
     * 
     * @param config the configuration for retry behavior and JWT token management.
     *               If null, default configuration will be used.
     * @return a new HxClient instance
     */
    public static HxClient create(HxConfig config) {
        if (config == null) {
            config = HxConfig.builder().build();
        }
        final HttpClient client = HttpClient.newHttpClient();
        return new HxClient(client, config);
    }

    /**
     * Creates a new HxClient with a default HttpClient and default configuration.
     * 
     * @return a new HxClient instance
     */
    public static HxClient create() {
        return create(HxConfig.builder().build());
    }

    /**
     * Creates a new HxClient with the specified HttpClient and configuration.
     * 
     * @param httpClient the underlying HttpClient to wrap with retry and JWT functionality
     * @param config the configuration for retry behavior and JWT token management.
     *               If null, default configuration will be used.
     * @return a new HxClient instance
     */
    public static HxClient create(HttpClient httpClient, HxConfig config) {
        if (config == null) {
            config = HxConfig.builder().build();
        }
        return new HxClient(httpClient, config);
    }

    /**
     * Creates a new HxClient with the specified HttpClient and default configuration.
     * 
     * @param httpClient the underlying HttpClient to wrap with retry and JWT functionality
     * @return a new HxClient instance
     */
    public static HxClient create(HttpClient httpClient) {
        return create(httpClient, HxConfig.builder().build());
    }

    /**
     * Creates a new HxClient with a default HttpClient and configuration based on a generic Retryable.Config.
     * 
     * <p>This factory method allows creating HxClient instances from any Retryable.Config source,
     * making it easy to integrate with existing retry configurations while using default HTTP-specific settings.
     * 
     * @param retryConfig the retry configuration to use. HTTP-specific settings (JWT tokens, retry status codes)
     *                   will use their default values. If null, default configuration will be used.
     * @return a new HxClient instance
     */
    public static HxClient create(Retryable.Config retryConfig) {
        final HxConfig config = HxConfig.builder()
                .withRetryConfig(retryConfig)
                .build();
        return create(config);
    }

    /**
     * Creates a new HxClient with the specified HttpClient and configuration based on a generic Retryable.Config.
     * 
     * <p>This factory method allows creating HxClient instances from any Retryable.Config source,
     * making it easy to integrate with existing retry configurations while using default HTTP-specific settings.
     * 
     * @param httpClient the underlying HttpClient to wrap with retry and JWT functionality
     * @param retryConfig the retry configuration to use. HTTP-specific settings (JWT tokens, retry status codes)
     *                   will use their default values. If null, default configuration will be used.
     * @return a new HxClient instance
     */
    public static HxClient create(HttpClient httpClient, Retryable.Config retryConfig) {
        final HxConfig config = HxConfig.builder()
                .withRetryConfig(retryConfig)
                .build();
        return create(httpClient, config);
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
     * <p>This method handles the core retry functionality including:
     * <ul>
     *   <li>Adding authentication headers</li>
     *   <li>Executing retry policy for network errors and specific HTTP status codes</li>
     *   <li>Attempting token refresh on 401 responses</li>
     *   <li>Re-executing requests with refreshed tokens</li>
     * </ul>
     * 
     * @param <T> the response body type
     * @param request the HTTP request to send
     * @param responseBodyHandler the response body handler
     * @return the HTTP response after successful execution or retry exhaustion
     * @throws IOException if all retry attempts fail
     * @throws InterruptedException if the operation is interrupted
     */
    protected <T> HttpResponse<T> sendWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) 
            throws IOException, InterruptedException {
        final boolean[] tokenRefreshed = {false};
        
        final Retryable<HttpResponse<T>> retry = Retryable.<HttpResponse<T>>of(config)
                .retryCondition(this::shouldRetryOnException)
                .retryIf(this::shouldRetryOnResponse)
                .onRetry(event -> {
                    String message = event.getFailure() != null ? event.getFailure().getMessage() 
                            : String.valueOf(event.getResult().statusCode());
                    log.debug("HTTP request retry attempt {}: {}", event.getAttempt(), message);
                });

        return retry.apply(() -> {
            final HttpRequest actualRequest = tokenManager.addAuthHeader(request);
            final HttpResponse<T> response = httpClient.send(actualRequest, responseBodyHandler);
            
            if (response.statusCode() == 401 && !tokenRefreshed[0]) {
                // Try JWT token refresh first if available
                if (tokenManager.canRefreshToken()) {
                    log.debug("Received 401 status, attempting coordinated token refresh");
                    try {
                        if (tokenManager.getOrRefreshTokenAsync().get()) {
                            tokenRefreshed[0] = true;
                            final HttpRequest refreshedRequest = tokenManager.addAuthHeader(request);
                            closeResponse(response);
                            return httpClient.send(refreshedRequest, responseBodyHandler);
                        }
                    } catch (Exception e) {
                        log.warn("Token refresh failed: " + e.getMessage());
                    }
                }
                
                // Try WWW-Authenticate challenge handling if enabled
                if (config.isWwwAuthenticateEnabled()) {
                    HttpRequest authenticatedRequest = handleWwwAuthenticate(request, response);
                    if (authenticatedRequest != null) {
                        log.debug("Retrying request with WWW-Authenticate credentials");
                        closeResponse(response);
                        return httpClient.send(authenticatedRequest, responseBodyHandler);
                    }
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
        
        final Retryable<HttpResponse<T>> retry = Retryable.<HttpResponse<T>>of(config)
                .retryCondition(this::shouldRetryOnException)
                .retryIf(this::shouldRetryOnResponse)
                .onRetry(event -> {
                    String message = event.getFailure() != null ? event.getFailure().getMessage() 
                            : String.valueOf(event.getResult().statusCode());
                    log.debug("HTTP async request retry attempt {}: {}", event.getAttempt(), message);
                });

        return retry.applyAsync(() -> {
            // Perform the HTTP request and coordinated JWT refresh within the retry
            final HttpRequest actualRequest = tokenManager.addAuthHeader(request);
            final HttpResponse<T> response = httpClient.sendAsync(actualRequest, responseBodyHandler).get();
            
            if (response.statusCode() == 401) {
                // Try JWT token refresh first if available
                if (tokenManager.canRefreshToken()) {
                    log.debug("Received 401 status in async call, attempting coordinated token refresh");
                    try {
                        final boolean refreshed = tokenManager.getOrRefreshTokenAsync().get();
                        if (refreshed) {
                            final HttpRequest refreshedRequest = tokenManager.addAuthHeader(request);
                            closeResponse(response);
                            return httpClient.sendAsync(refreshedRequest, responseBodyHandler).get();
                        }
                    } catch (Exception e) {
                        log.warn("Async token refresh failed: " + e.getMessage());
                    }
                }
                
                // Try WWW-Authenticate challenge handling if enabled
                if (config.isWwwAuthenticateEnabled()) {
                    HttpRequest authenticatedRequest = handleWwwAuthenticate(request, response);
                    if (authenticatedRequest != null) {
                        log.debug("Retrying async request with WWW-Authenticate credentials");
                        closeResponse(response);
                        return httpClient.sendAsync(authenticatedRequest, responseBodyHandler).get();
                    }
                }
            }
            
            return response;
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
}
