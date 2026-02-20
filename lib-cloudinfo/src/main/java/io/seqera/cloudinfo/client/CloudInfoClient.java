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

package io.seqera.cloudinfo.client;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

import io.seqera.cloudinfo.api.CloudProduct;
import io.seqera.cloudinfo.api.CloudRegion;
import io.seqera.cloudinfo.api.CloudResponse;
import io.seqera.http.HxClient;
import io.seqera.serde.jackson.JacksonEncodingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for the Cloudinfo API.
 *
 * <p>This client provides methods to fetch cloud provider information including
 * regions and compute products (instance types) with their pricing.
 *
 * <p>Usage example:
 * <pre>{@code
 * CloudInfoClient client = CloudInfoClient.builder()
 *     .endpoint("https://cloudinfo.seqera.io")
 *     .build();
 *
 * List<CloudRegion> regions = client.getRegions("amazon");
 * List<CloudProduct> products = client.getProducts("amazon", "us-east-1");
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CloudInfoClient {

    private static final Logger log = LoggerFactory.getLogger(CloudInfoClient.class);

    public static final String DEFAULT_ENDPOINT = "https://cloudinfo.seqera.io";

    private static final JacksonEncodingStrategy<List<CloudRegion>> REGIONS_ENCODER =
            new JacksonEncodingStrategy<List<CloudRegion>>() {};

    private static final JacksonEncodingStrategy<CloudResponse> RESPONSE_ENCODER =
            new JacksonEncodingStrategy<CloudResponse>() {};

    private final String endpoint;
    private final HxClient httpClient;

    /**
     * Creates a CloudInfoClient with the specified endpoint and HTTP client.
     *
     * @param endpoint the base URL for the Cloudinfo API
     * @param httpClient the HxClient instance to use for HTTP requests
     */
    protected CloudInfoClient(String endpoint, HxClient httpClient) {
        this.endpoint = endpoint != null ? endpoint : DEFAULT_ENDPOINT;
        this.httpClient = httpClient != null ? httpClient : HxClient.newHxClient();
    }

    /**
     * Creates a new Builder instance for constructing CloudInfoClient objects.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a CloudInfoClient with default settings.
     *
     * @return a new CloudInfoClient instance with default configuration
     */
    public static CloudInfoClient create() {
        return builder().build();
    }

    /**
     * Gets the list of available regions for a cloud provider.
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @return list of available regions
     * @throws CloudInfoException if the request fails
     */
    public List<CloudRegion> getRegions(String provider) {
        String path = String.format("/api/v1/providers/%s/services/compute/regions", provider);
        log.trace("CloudInfo regions: {}", path);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.sendAsString(request);

            if (response.statusCode() != 200) {
                throw new CloudInfoException(
                        String.format("Failed to fetch regions for provider=%s, status=%d", provider, response.statusCode()),
                        response.statusCode());
            }

            return REGIONS_ENCODER.decode(response.body());
        } catch (CloudInfoException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudInfoException(
                    String.format("Failed to fetch regions for provider=%s", provider), e);
        }
    }

    /**
     * Gets the list of region IDs for a cloud provider.
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @return list of region IDs
     * @throws CloudInfoException if the request fails
     */
    public List<String> getRegionIds(String provider) {
        return getRegions(provider).stream()
                .map(CloudRegion::getId)
                .toList();
    }

    /**
     * Gets the list of compute products for a cloud provider and region.
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @param region the region identifier (e.g., "us-east-1", "europe-west1")
     * @return list of compute products with pricing
     * @throws CloudInfoException if the request fails
     */
    public List<CloudProduct> getProducts(String provider, String region) {
        String path = String.format("/api/v1/providers/%s/services/compute/regions/%s/products", provider, region);
        log.trace("CloudInfo products: {}", path);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + path))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.sendAsString(request);

            if (response.statusCode() != 200) {
                throw new CloudInfoException(
                        String.format("Failed to fetch products for provider=%s, region=%s, status=%d", provider, region, response.statusCode()),
                        response.statusCode());
            }

            CloudResponse cloudResponse = RESPONSE_ENCODER.decode(response.body());
            return cloudResponse.getProducts() != null ? cloudResponse.getProducts() : Collections.emptyList();
        } catch (CloudInfoException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudInfoException(
                    String.format("Failed to fetch products for provider=%s, region=%s", provider, region), e);
        }
    }

    /**
     * Gets the API endpoint URL.
     *
     * @return the base URL for the Cloudinfo API
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Builder class for constructing CloudInfoClient instances.
     */
    public static class Builder {
        private String endpoint = DEFAULT_ENDPOINT;
        private HxClient httpClient;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private int maxRetries = 3;

        /**
         * Sets the API endpoint URL.
         *
         * @param endpoint the base URL for the Cloudinfo API
         * @return this Builder instance
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Sets a custom HxClient instance.
         *
         * @param httpClient the HxClient to use for HTTP requests
         * @return this Builder instance
         */
        public Builder httpClient(HxClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Sets the connection timeout.
         *
         * @param timeout the connection timeout duration
         * @return this Builder instance
         */
        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        /**
         * Sets the maximum number of retry attempts.
         *
         * @param maxRetries the maximum number of retries
         * @return this Builder instance
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Builds and returns a new CloudInfoClient instance.
         *
         * @return a new CloudInfoClient instance
         */
        public CloudInfoClient build() {
            HxClient client = this.httpClient;
            if (client == null) {
                client = HxClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .maxAttempts(maxRetries)
                        .build();
            }
            return new CloudInfoClient(endpoint, client);
        }
    }
}
