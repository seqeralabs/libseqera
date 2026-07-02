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

package io.seqera.cloudinfo.api;

import java.util.List;
import java.util.Objects;

/**
 * Optional query parameters for the Cloudinfo products endpoint.
 *
 * <p>Each filter is applied server-side; unset filters (the default) leave the
 * corresponding restriction disabled and all products are returned.
 *
 * <p>Filters:
 * <ul>
 *   <li>{@link Builder#features(List)} — capability feature tokens to intersect
 *       (AND). A product matches only if its {@link CloudProduct#getFeatures()
 *       features} contain <em>all</em> the requested tokens (e.g.
 *       {@code ["gpu", "nvidia"]} → {@code ?features=gpu,nvidia}). Tokens are
 *       lowercase; see {@link CloudProduct#getFeatures()} for the vocabulary.</li>
 *   <li>{@link Builder#families(List)} — machine-family names and/or
 *       fully-qualified instance-type names to keep (e.g.
 *       {@code ["m5d", "c5.large"]} → {@code ?families=m5d,c5.large}). A product
 *       matches if its type equals an identifier or its family prefix does.</li>
 *   <li>{@link Builder#sched(boolean)} / {@link Builder#nvme(boolean)} — the
 *       original boolean filters. Retained for backward compatibility; the
 *       backend treats {@code sched=true} as {@code features=sched} and
 *       {@code nvme=true} as {@code features=ssd} (the local-storage capability
 *       is provider-neutral {@code ssd}). Prefer {@code features} for new code.</li>
 * </ul>
 * When more than one filter is set they compose with AND semantics.
 *
 * <p>Implements value-based {@link #equals(Object)} and {@link #hashCode()} so
 * instances can be used as map keys / set members and so consumers can rely on
 * a deterministic hash for caching. When new filter fields are added, update
 * both methods to include them — {@code ProductsQueryTest} guards this with a
 * reflection-based check that fails if any declared field is missing from the
 * hash.
 *
 * <p>Usage example:
 * <pre>{@code
 * ProductsQuery query = ProductsQuery.builder()
 *     .features(List.of("gpu", "nvidia"))
 *     .families(List.of("p4d"))
 *     .build();
 *
 * List<CloudProduct> products = client.getProducts("amazon", "us-east-1", query);
 * }</pre>
 */
public class ProductsQuery {

    private final boolean sched;
    private final boolean nvme;
    private final List<String> features;
    private final List<String> families;

    private ProductsQuery(Builder builder) {
        this.sched = builder.sched;
        this.nvme = builder.nvme;
        this.features = builder.features == null ? null : List.copyOf(builder.features);
        this.families = builder.families == null ? null : List.copyOf(builder.families);
    }

    /**
     * Whether to filter results to Scheduler-supported instance families.
     *
     * @return {@code true} if the {@code sched=true} filter should be applied
     */
    public boolean isSched() {
        return sched;
    }

    /**
     * Whether to filter results to instance families with local NVMe/SSD storage.
     *
     * @return {@code true} if the {@code nvme=true} filter should be applied
     */
    public boolean isNvme() {
        return nvme;
    }

    /**
     * The capability feature tokens to intersect (AND) when filtering products.
     *
     * @return an immutable list of feature tokens, or {@code null} when the
     *         {@code features} filter is not set
     */
    public List<String> getFeatures() {
        return features;
    }

    /**
     * The machine-family and/or instance-type identifiers to filter products by.
     *
     * @return an immutable list of family/type identifiers, or {@code null} when
     *         the {@code families} filter is not set
     */
    public List<String> getFamilies() {
        return families;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductsQuery)) return false;
        ProductsQuery that = (ProductsQuery) o;
        return sched == that.sched
                && nvme == that.nvme
                && Objects.equals(features, that.features)
                && Objects.equals(families, that.families);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sched, nvme, features, families);
    }

    @Override
    public String toString() {
        return "ProductsQuery{sched=" + sched
                + ", nvme=" + nvme
                + ", features=" + features
                + ", families=" + families
                + '}';
    }

    /**
     * Creates a new Builder instance for constructing ProductsQuery objects.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing ProductsQuery instances.
     */
    public static class Builder {
        private boolean sched;
        private boolean nvme;
        private List<String> features;
        private List<String> families;

        /**
         * Enables the Scheduler-supported filter on the products endpoint.
         *
         * @param sched {@code true} to apply the {@code sched=true} filter
         * @return this Builder instance
         */
        public Builder sched(boolean sched) {
            this.sched = sched;
            return this;
        }

        /**
         * Enables the NVMe/SSD local-storage filter on the products endpoint.
         *
         * @param nvme {@code true} to apply the {@code nvme=true} filter
         * @return this Builder instance
         */
        public Builder nvme(boolean nvme) {
            this.nvme = nvme;
            return this;
        }

        /**
         * Restricts results to products whose capability features contain all of
         * the given tokens (AND). Serialized as {@code ?features=a,b,c}. Passing
         * {@code null} or an empty list leaves the filter disabled.
         *
         * @param features the lowercase capability tokens to intersect
         * @return this Builder instance
         */
        public Builder features(List<String> features) {
            this.features = features;
            return this;
        }

        /**
         * Restricts results to products whose machine family or instance type
         * matches one of the given identifiers. Serialized as
         * {@code ?families=m5d,c5.large}. Passing {@code null} or an empty list
         * leaves the filter disabled.
         *
         * @param families the family names and/or instance-type names to keep
         * @return this Builder instance
         */
        public Builder families(List<String> families) {
            this.families = families;
            return this;
        }

        /**
         * Builds and returns a new ProductsQuery instance.
         *
         * @return a new ProductsQuery instance
         */
        public ProductsQuery build() {
            return new ProductsQuery(this);
        }
    }
}
