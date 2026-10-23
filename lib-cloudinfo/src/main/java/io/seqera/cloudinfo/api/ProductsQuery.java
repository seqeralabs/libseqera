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
 * gpuModels, minGpuCapability, cuda, minGpuMemoryGB - GPU filters, from cloudinfo
 * 0.30.0; an older backend ignores them and returns every product.
 *
 * Value-based equals/hashCode make instances safe as map or cache keys. Any new
 * field must be added to both; ProductsQueryTest guards this by reflection.
 */
public class ProductsQuery {

    private final boolean sched;
    private final boolean nvme;
    private final List<String> features;
    private final List<String> families;
    private final List<String> gpuModels;
    private final String minGpuCapability;
    private final String cuda;
    private final Long minGpuMemoryGB;

    private ProductsQuery(Builder builder) {
        this.sched = builder.sched;
        this.nvme = builder.nvme;
        this.features = builder.features == null ? null : List.copyOf(builder.features);
        this.families = builder.families == null ? null : List.copyOf(builder.families);
        this.gpuModels = builder.gpuModels == null ? null : List.copyOf(builder.gpuModels);
        this.minGpuCapability = builder.minGpuCapability;
        this.cuda = builder.cuda;
        this.minGpuMemoryGB = builder.minGpuMemoryGB;
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

    /** Canonical GPU model IDs to match (OR), or null when unset. Immutable. */
    public List<String> getGpuModels() {
        return gpuModels;
    }

    /** Minimum GPU compute capability ("major.minor"), or null when unset. */
    public String getMinGpuCapability() {
        return minGpuCapability;
    }

    /** CUDA toolkit release ("major.minor") the GPU must support, or null when unset. */
    public String getCuda() {
        return cuda;
    }

    /** Minimum GPU memory per GPU, in GB, or null when unset. */
    public Long getMinGpuMemoryGB() {
        return minGpuMemoryGB;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductsQuery)) return false;
        ProductsQuery that = (ProductsQuery) o;
        return sched == that.sched
                && nvme == that.nvme
                && Objects.equals(features, that.features)
                && Objects.equals(families, that.families)
                && Objects.equals(gpuModels, that.gpuModels)
                && Objects.equals(minGpuCapability, that.minGpuCapability)
                && Objects.equals(cuda, that.cuda)
                && Objects.equals(minGpuMemoryGB, that.minGpuMemoryGB);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sched, nvme, features, families, gpuModels, minGpuCapability, cuda, minGpuMemoryGB);
    }

    @Override
    public String toString() {
        return "ProductsQuery{sched=" + sched
                + ", nvme=" + nvme
                + ", features=" + features
                + ", families=" + families
                + ", gpuModels=" + gpuModels
                + ", minGpuCapability=" + minGpuCapability
                + ", cuda=" + cuda
                + ", minGpuMemoryGB=" + minGpuMemoryGB
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
        private List<String> gpuModels;
        private String minGpuCapability;
        private String cuda;
        private Long minGpuMemoryGB;

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

        /**
         * Keeps only products whose canonical gpuModel is any of the given IDs
         * (?gpuModel=nvidia-t4,nvidia-l4). Unknown IDs match nothing. Null or empty disables it.
         */
        public Builder gpuModels(List<String> gpuModels) {
            this.gpuModels = gpuModels;
            return this;
        }

        /**
         * Keeps only products whose GPU compute capability is at least this
         * "major.minor" version, compared numerically (?minGpuCapability=8.0 keeps
         * Ampere and newer). The backend answers 400 to a malformed value. Null disables it.
         */
        public Builder minGpuCapability(String minGpuCapability) {
            this.minGpuCapability = minGpuCapability;
            return this;
        }

        /**
         * Keeps only products whose GPU the given "major.minor" CUDA toolkit release
         * can target (?cuda=12.4). The backend answers 400 to a malformed value. Null disables it.
         */
        public Builder cuda(String cuda) {
            this.cuda = cuda;
            return this;
        }

        /**
         * Keeps only products with at least this many GB of memory per GPU, a
         * fractional GPU counting its share of the card (?minGpuMemoryGB=40). Must be
         * positive, else the backend answers 400. Null disables it.
         */
        public Builder minGpuMemoryGB(Long minGpuMemoryGB) {
            this.minGpuMemoryGB = minGpuMemoryGB;
            return this;
        }

        /** Builds the ProductsQuery. */
        public ProductsQuery build() {
            return new ProductsQuery(this);
        }
    }
}
