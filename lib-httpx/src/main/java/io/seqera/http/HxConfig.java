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

import java.time.Duration;
import java.util.Set;

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
 * HxConfig config = HxConfig.builder().build();
 * 
 * // Custom retry configuration
 * HxConfig config = HxConfig.builder()
 *     .withMaxAttempts(3)
 *     .withDelay(Duration.ofSeconds(1))
 *     .withRetryStatusCodes(Set.of(429, 503))
 *     .build();
 * 
 * // With JWT token configuration
 * HxConfig config = HxConfig.builder()
 *     .withJwtToken("your-jwt-token")
 *     .withRefreshToken("your-refresh-token")
 *     .withRefreshTokenUrl("https://api.example.com/oauth/token")
 *     .build();
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class HxConfig implements Retryable.Config {

    private Duration delay = Duration.ofMillis(500);
    private Duration maxDelay = Duration.ofSeconds(30);
    private int maxAttempts = 5;
    private double jitter = 0.25d;
    private double multiplier = 2.0;

    private Set<Integer> retryStatusCodes = Set.of(429, 500, 502, 503, 504);

    private String jwtToken;
    private String refreshToken;  
    private String refreshTokenUrl;
    private Duration tokenRefreshTimeout = Duration.ofSeconds(30);

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

    /**
     * Creates a new builder instance for constructing HttpConfig objects.
     * 
     * @return a new Builder with default values pre-populated
     */
    public static Builder builder() {
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
        private Set<Integer> retryStatusCodes = Set.of(429, 500, 502, 503, 504);
        private String jwtToken;
        private String refreshToken;
        private String refreshTokenUrl;
        private Duration tokenRefreshTimeout = Duration.ofSeconds(30);

        public Builder withDelay(Duration delay) {
            this.delay = delay;
            return this;
        }

        public Builder withMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder withMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder withJitter(double jitter) {
            this.jitter = jitter;
            return this;
        }

        public Builder withMultiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        public Builder withRetryStatusCodes(Set<Integer> retryStatusCodes) {
            this.retryStatusCodes = retryStatusCodes;
            return this;
        }

        public Builder withJwtToken(String jwtToken) {
            this.jwtToken = jwtToken;
            return this;
        }

        public Builder withRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }

        public Builder withRefreshTokenUrl(String refreshTokenUrl) {
            this.refreshTokenUrl = refreshTokenUrl;
            return this;
        }

        public Builder withTokenRefreshTimeout(Duration tokenRefreshTimeout) {
            this.tokenRefreshTimeout = tokenRefreshTimeout;
            return this;
        }

        /**
         * Configures retry settings from a generic Retryable.Config instance.
         * This allows building HttpConfig with retry configuration from other sources.
         * 
         * @param retryConfig the Retryable.Config to copy retry settings from
         * @return this builder instance for method chaining
         */
        public Builder withRetryConfig(Retryable.Config retryConfig) {
            if (retryConfig != null) {
                this.delay = retryConfig.getDelay();
                this.maxDelay = retryConfig.getMaxDelay();
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
            config.retryStatusCodes = this.retryStatusCodes;
            config.jwtToken = this.jwtToken;
            config.refreshToken = this.refreshToken;
            config.refreshTokenUrl = this.refreshTokenUrl;
            config.tokenRefreshTimeout = this.tokenRefreshTimeout;
            return config;
        }
    }
}
