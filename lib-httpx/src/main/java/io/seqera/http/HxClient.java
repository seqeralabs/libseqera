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

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
            
            if (response.statusCode() == 401 && !tokenRefreshed[0] && tokenManager.canRefreshToken()) {
                log.debug("Received 401 status, attempting coordinated token refresh");
                try {
                    if (tokenManager.getOrRefreshTokenAsync().get()) {
                        tokenRefreshed[0] = true;
                        final HttpRequest refreshedRequest = tokenManager.addAuthHeader(request);
                        return httpClient.send(refreshedRequest, responseBodyHandler);
                    }
                } catch (Exception e) {
                    log.warn("Token refresh failed: " + e.getMessage());
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
            
            if (response.statusCode() == 401 && tokenManager.canRefreshToken()) {
                log.debug("Received 401 status in async call, attempting coordinated token refresh");
                try {
                    final boolean refreshed = tokenManager.getOrRefreshTokenAsync().get();
                    if (refreshed) {
                        final HttpRequest refreshedRequest = tokenManager.addAuthHeader(request);
                        return httpClient.sendAsync(refreshedRequest, responseBodyHandler).get();
                    }
                } catch (Exception e) {
                    log.warn("Async token refresh failed: " + e.getMessage());
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
}
