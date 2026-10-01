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
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.seqera.cloudinfo.api.CloudProduct;
import io.seqera.cloudinfo.api.CloudRegion;
import io.seqera.cloudinfo.api.CloudResponse;
import io.seqera.cloudinfo.api.ErrorResponse;
import io.seqera.cloudinfo.api.FamiliesResponse;
import io.seqera.cloudinfo.api.ProductsQuery;
import io.seqera.cloudinfo.api.StoragePrices;
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
 * <p>Every method throws {@link NullPointerException} for a null
 * {@code provider}, {@code region} or filter token and
 * {@link IllegalArgumentException} for an empty {@code provider} or
 * {@code region}, before any request is sent. Request failures throw
 * {@link CloudInfoException}.
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

    private static final JacksonEncodingStrategy<StoragePrices> STORAGE_ENCODER =
            new JacksonEncodingStrategy<StoragePrices>() {};

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
        String what = String.format("regions for provider=%s", provider);
        String url = path("providers", provider, "services", "compute", "regions");
        List<CloudRegion> regions = fetch(url, Duration.ofSeconds(30), what, REGIONS_ENCODER);
        return regions != null ? regions : Collections.emptyList();
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
        String what = String.format("products for provider=%s, region=%s", provider, region);
        String url = path("providers", provider, "services", "compute", "regions", region, "products")
                + buildQueryString(query);
        CloudResponse cloudResponse = fetch(url, Duration.ofSeconds(60), what, RESPONSE_ENCODER);
        return cloudResponse != null && cloudResponse.getProducts() != null
                ? cloudResponse.getProducts()
                : Collections.emptyList();
    }

    /**
     * Gets the on-demand block-storage (disk) prices of a region.
     *
     * <p>Only amazon, azure and google scrape storage prices. For an enabled
     * provider that does not scrape them, or a known region not scraped yet,
     * CloudInfo answers 404 with an RFC 7807 problem body
     * ({@code "title":"Not Found","status":404}); this method then returns an
     * empty {@link Optional}, so callers can fall back to their own prices. Any
     * other 404 (a backend older than 0.25.0 without the endpoint, a wrong
     * endpoint or a proxy) throws, as does an unknown provider or region (400).
     *
     * @param provider the cloud provider identifier (e.g., "amazon", "google", "azure")
     * @param region the region identifier (e.g., "us-east-1", "europe-west1")
     * @return the region's storage prices, or empty when CloudInfo has none
     * @throws CloudInfoException if the request fails, including a 404 that is not CloudInfo's "no prices" answer
     */
    public Optional<StoragePrices> getStoragePrices(String provider, String region) {
        String what = String.format("storage prices for provider=%s, region=%s", provider, region);
        String url = path("providers", provider, "services", "compute", "regions", region, "storage");
        HttpResponse<String> response = get(url, Duration.ofSeconds(30), what);
        if (response.statusCode() != 200) {
            ErrorResponse error = decodeError(response);
            if (response.statusCode() == 404 && isNoStoragePricesProblem(error)) {
                log.debug("CloudInfo has no storage prices for provider={}, region={}", provider, region);
                return Optional.empty();
            }
            throw apiError(what, response.statusCode(), error);
        }
        return Optional.ofNullable(decode(STORAGE_ENCODER, response, what));
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
     * Gets the machine families for a provider, restricted to those having at
     * least one product with all the given capability features. Tokens must be
     * lowercase; an unknown or non-lowercase token yields HTTP 400, surfaced as a
     * CloudInfoException whose getValidCapabilities() lists the accepted tokens.
     *
     * @param provider the cloud provider identifier (e.g. amazon, google, azure)
     * @param features capability tokens to intersect (AND); may be null or empty
     * @return the sorted distinct machine-family names matching the query
     * @throws CloudInfoException if the request fails
     */
    public List<String> getFamilies(String provider, List<String> features) {
        String what = String.format("families for provider=%s", provider);
        String url = path("providers", provider, "families") + buildFeaturesQueryString(features);
        FamiliesResponse familiesResponse = fetch(url, Duration.ofSeconds(60), what, FAMILIES_ENCODER);
        return familiesResponse != null && familiesResponse.getFamilies() != null
                ? familiesResponse.getFamilies()
                : Collections.emptyList();
    }

    /**
     * Sends a GET request to {@code url} and decodes a 200 body with
     * {@code encoder}; any other status throws via {@link #apiError}.
     */
    private <T> T fetch(String url, Duration timeout, String what, JacksonEncodingStrategy<T> encoder) {
        HttpResponse<String> response = get(url, timeout, what);
        if (response.statusCode() != 200) {
            throw apiError(what, response.statusCode(), decodeError(response));
        }
        return decode(encoder, response, what);
    }

    /**
     * Sends a GET request to {@code url}, wrapping any failure to build or send
     * it in a CloudInfoException without a status.
     */
    private HttpResponse<String> get(String url, Duration timeout, String what) {
        log.trace("CloudInfo {}: {}", what, url);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(timeout)
                    .build();
            return httpClient.sendAsString(request);
        } catch (Exception e) {
            throw new CloudInfoException("Failed to fetch " + what, e);
        }
    }

    /**
     * Decodes a 200 body, wrapping a malformed one in a CloudInfoException
     * without a status.
     */
    private static <T> T decode(JacksonEncodingStrategy<T> encoder, HttpResponse<String> response, String what) {
        try {
            return encoder.decode(response.body());
        } catch (Exception e) {
            throw new CloudInfoException("Failed to fetch " + what, e);
        }
    }

    /**
     * Builds {@code <endpoint>/api/v1/<segments>}, encoding every segment with
     * {@link #segment}, so no path parameter can reach the URL unescaped.
     */
    private String path(String... segments) {
        StringBuilder sb = new StringBuilder(endpoint).append("/api/v1");
        for (String it : segments) {
            sb.append('/').append(segment(it));
        }
        return sb.toString();
    }

    /**
     * Percent-encodes a value as a single URL path segment, so characters such
     * as {@code ?}, {@code #}, {@code /} or a space stay part of the segment
     * instead of changing the requested URL. The dot segments {@code .} and
     * {@code ..} are encoded too, so the client does not resolve them as
     * relative paths. A server or proxy may still decode {@code %2F} and dot
     * segments before routing (cloudinfo's gin router matches on the decoded
     * path), which then fails with a 404 rather than reaching another endpoint.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if {@code value} is empty
     */
    static String segment(String value) {
        Objects.requireNonNull(value, "CloudInfo path parameter must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("CloudInfo path parameter must not be empty");
        }
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ".".equals(encoded) || "..".equals(encoded)
                ? encoded.replace(".", "%2E")
                : encoded;
    }

    /**
     * Decodes a non-200 body as {@link ErrorResponse}, or returns null when it
     * has another shape (e.g. a plain-text 404 from a proxy).
     */
    private static ErrorResponse decodeError(HttpResponse<String> response) {
        try {
            return ERROR_ENCODER.decode(response.body());
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * True for cloudinfo's /storage "no prices" answer: an RFC 7807 problem with
     * {@code "title":"Not Found","status":404}. Other JSON 404s (e.g. a proxy's
     * Spring Boot error body, which also carries {@code "status":404} but uses
     * {@code "error"} rather than {@code "title"}) do not match.
     */
    private static boolean isNoStoragePricesProblem(ErrorResponse error) {
        return error != null
                && Integer.valueOf(404).equals(error.getStatus())
                && "Not Found".equals(error.getTitle());
    }

    /**
     * Builds a CloudInfoException for a non-200 response, adding the server's
     * error message (families {@code error} or RFC 7807 {@code detail}) and
     * validCapabilities when the body carries them.
     */
    private static CloudInfoException apiError(String what, int status, ErrorResponse error) {
        String detail = null;
        List<String> validCapabilities = null;
        if (error != null) {
            detail = error.getError() != null ? error.getError() : error.getDetail();
            validCapabilities = error.getValidCapabilities();
        }
        String message = detail != null
                ? String.format("Failed to fetch %s, status=%d: %s", what, status, detail)
                : String.format("Failed to fetch %s, status=%d", what, status);
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

    /** Builds ?features=a,b,c for the families endpoint, or an empty string. */
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
                .map(v -> URLEncoder.encode(Objects.requireNonNull(v, name + " must not contain null"), StandardCharsets.UTF_8))
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
