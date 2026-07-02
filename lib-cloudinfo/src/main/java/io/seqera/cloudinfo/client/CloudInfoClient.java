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
 *
 */

package io.seqera.cloudinfo.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import io.seqera.cloudinfo.api.CloudProduct;
import io.seqera.cloudinfo.api.CloudRegion;
import io.seqera.cloudinfo.api.CloudResponse;
import io.seqera.cloudinfo.api.ErrorResponse;
import io.seqera.cloudinfo.api.FamiliesResponse;
import io.seqera.cloudinfo.api.ProductsQuery;
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

    private static final JacksonEncodingStrategy<FamiliesResponse> FAMILIES_ENCODER =
            new JacksonEncodingStrategy<FamiliesResponse>() {};

    private static final JacksonEncodingStrategy<ErrorResponse> ERROR_ENCODER =
            new JacksonEncodingStrategy<ErrorResponse>() {};

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
        return getProducts(provider, region, null);
    }

    /**
     * Gets the list of compute products for a cloud provider and region, applying
     * the optional filters in {@code query}.
     *
     * <p>When {@code query} is {@code null} or has no flags set, the request is
     * equivalent to {@link #getProducts(String, String)} and all products are
     * returned. Unknown filters for a provider are ignored server-side.
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @param region the region identifier (e.g., "us-east-1", "europe-west1")
     * @param query optional filters to apply to the request; may be {@code null}
     * @return list of compute products with pricing
     * @throws CloudInfoException if the request fails
     */
    public List<CloudProduct> getProducts(String provider, String region, ProductsQuery query) {
        String path = String.format("/api/v1/providers/%s/services/compute/regions/%s/products", provider, region);
        String url = endpoint + path + buildQueryString(query);
        log.trace("CloudInfo products: {}", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
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
     * Gets the machine families for a cloud provider.
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @return the sorted list of distinct machine-family names
     * @throws CloudInfoException if the request fails
     */
    public List<String> getFamilies(String provider) {
        return getFamilies(provider, null);
    }

    /**
     * Gets the machine families for a cloud provider, optionally restricted to
     * those having at least one product with all of the given capability features.
     *
     * <p>The feature tokens must be lowercase (see {@link CloudProduct#getFeatures()}
     * for the vocabulary); the endpoint rejects an unknown or non-lowercase token
     * with HTTP 400, surfaced here as a {@link CloudInfoException} whose
     * {@link CloudInfoException#getValidCapabilities()} lists the accepted tokens.
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @param features capability feature tokens to intersect (AND); may be {@code null} or empty
     * @return the sorted list of distinct machine-family names matching the query
     * @throws CloudInfoException if the request fails
     */
    public List<String> getFamilies(String provider, List<String> features) {
        String path = String.format("/api/v1/providers/%s/families", provider);
        String url = endpoint + path + buildFeaturesQueryString(features);
        log.trace("CloudInfo families: {}", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.sendAsString(request);

            if (response.statusCode() != 200) {
                throw familiesError(provider, response);
            }

            FamiliesResponse familiesResponse = FAMILIES_ENCODER.decode(response.body());
            return familiesResponse != null && familiesResponse.getFamilies() != null
                    ? familiesResponse.getFamilies()
                    : Collections.emptyList();
        } catch (CloudInfoException e) {
            throw e;
        } catch (Exception e) {
            throw new CloudInfoException(
                    String.format("Failed to fetch families for provider=%s", provider), e);
        }
    }

    /**
     * Builds a {@link CloudInfoException} for a non-200 families response,
     * enriching it with the server's {@code error} message and
     * {@code validCapabilities} list when the body has that shape.
     */
    private static CloudInfoException familiesError(String provider, HttpResponse<String> response) {
        int status = response.statusCode();
        String detail = null;
        List<String> validCapabilities = null;
        try {
            ErrorResponse error = ERROR_ENCODER.decode(response.body());
            if (error != null) {
                detail = error.getError();
                validCapabilities = error.getValidCapabilities();
            }
        } catch (Exception ignore) {
            // body is not in the {error, validCapabilities} shape — fall back to a plain error
        }
        String message = detail != null
                ? String.format("Failed to fetch families for provider=%s, status=%d: %s", provider, status, detail)
                : String.format("Failed to fetch families for provider=%s, status=%d", provider, status);
        return new CloudInfoException(message, status, validCapabilities);
    }

    private static String buildQueryString(ProductsQuery query) {
        if (query == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (query.isSched()) {
            appendParam(sb, "sched=true");
        }
        if (query.isNvme()) {
            appendParam(sb, "nvme=true");
        }
        appendCsvParam(sb, "features", query.getFeatures());
        appendCsvParam(sb, "families", query.getFamilies());
        return sb.toString();
    }

    /**
     * Builds the query string for the families endpoint: {@code ?features=a,b,c}
     * when {@code features} is non-empty, otherwise an empty string.
     */
    private static String buildFeaturesQueryString(List<String> features) {
        StringBuilder sb = new StringBuilder();
        appendCsvParam(sb, "features", features);
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String param) {
        sb.append(sb.length() == 0 ? '?' : '&').append(param);
    }

    private static void appendCsvParam(StringBuilder sb, String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String joined = values.stream()
                .map(v -> URLEncoder.encode(v, StandardCharsets.UTF_8))
                .collect(Collectors.joining(","));
        appendParam(sb, name + "=" + joined);
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
