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

/**
 * Optional query parameters for the Cloudinfo products endpoint.
 *
 * <p>Each flag is applied server-side; unset flags (the default) leave the
 * corresponding filter disabled and all products are returned.
 *
 * <p>Usage example:
 * <pre>{@code
 * ProductsQuery query = ProductsQuery.builder()
 *     .sched(true)
 *     .nvme(true)
 *     .build();
 *
 * List<CloudProduct> products = client.getProducts("amazon", "us-east-1", query);
 * }</pre>
 */
public class ProductsQuery {

    private final boolean sched;
    private final boolean nvme;

    private ProductsQuery(Builder builder) {
        this.sched = builder.sched;
        this.nvme = builder.nvme;
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
     * Whether to filter results to instance families with NVMe local storage.
     *
     * @return {@code true} if the {@code nvme=true} filter should be applied
     */
    public boolean isNvme() {
        return nvme;
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
         * Enables the NVMe local-storage filter on the products endpoint.
         *
         * @param nvme {@code true} to apply the {@code nvme=true} filter
         * @return this Builder instance
         */
        public Builder nvme(boolean nvme) {
            this.nvme = nvme;
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
