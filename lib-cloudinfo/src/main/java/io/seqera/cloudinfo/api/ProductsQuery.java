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
 * Optional filters for the Cloudinfo products endpoint. Filters are applied
 * server-side and compose with AND; unset filters return all products.
 *
 * features - capability tokens to intersect, sent as ?features=a,b,c.
 * families - machine-family or instance-type names, sent as ?families=m5d,c5.large.
 * sched/nvme - the original boolean filters, kept for backward compatibility
 * (the backend treats sched=true as features=sched and nvme=true as features=ssd).
 *
 * Value-based equals/hashCode make instances safe as map or cache keys. Any new
 * field must be added to both; ProductsQueryTest guards this by reflection.
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

    /** Whether the sched=true filter (Scheduler-supported families) is applied. */
    public boolean isSched() {
        return sched;
    }

    /** Whether the nvme=true filter (local NVMe/SSD storage) is applied. */
    public boolean isNvme() {
        return nvme;
    }

    /** Capability tokens to intersect (AND), or null when unset. Immutable. */
    public List<String> getFeatures() {
        return features;
    }

    /** Family/instance-type identifiers to filter by, or null when unset. Immutable. */
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

    /** Creates a new Builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for ProductsQuery. */
    public static class Builder {
        private boolean sched;
        private boolean nvme;
        private List<String> features;
        private List<String> families;

        /** Applies the sched=true filter. */
        public Builder sched(boolean sched) {
            this.sched = sched;
            return this;
        }

        /** Applies the nvme=true filter. */
        public Builder nvme(boolean nvme) {
            this.nvme = nvme;
            return this;
        }

        /** Keeps only products whose features contain all given tokens (?features=a,b,c). Null or empty disables it. */
        public Builder features(List<String> features) {
            this.features = features;
            return this;
        }

        /** Keeps only products in the given families or matching instance types (?families=m5d,c5.large). Null or empty disables it. */
        public Builder families(List<String> families) {
            this.families = families;
            return this;
        }

        /** Builds the ProductsQuery. */
        public ProductsQuery build() {
            return new ProductsQuery(this);
        }
    }
}
