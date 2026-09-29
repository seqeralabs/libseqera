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

import java.util.Objects;

/**
 * One fixed-size tier of a volume type billed per disk (azure P10, E10, S10, ...).
 */
public class VolumeTier {

    /** Provider tier name (azure P10, E10, S10, ...). */
    private String tier;
    /** Tier capacity, in GiB. */
    private Long sizeGiB;
    /** Price of one disk of this tier, in USD per month. */
    private Double pricePerMonth;
    /** Provisioned IOPS of the tier; null when not reported. */
    private Long iops;
    /** Provisioned throughput of the tier, in MiB/s; null when not reported. */
    private Long throughputMiBps;

    public VolumeTier() {
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public Long getSizeGiB() {
        return sizeGiB;
    }

    public void setSizeGiB(Long sizeGiB) {
        this.sizeGiB = sizeGiB;
    }

    public Double getPricePerMonth() {
        return pricePerMonth;
    }

    public void setPricePerMonth(Double pricePerMonth) {
        this.pricePerMonth = pricePerMonth;
    }

    public Long getIops() {
        return iops;
    }

    public void setIops(Long iops) {
        this.iops = iops;
    }

    public Long getThroughputMiBps() {
        return throughputMiBps;
    }

    public void setThroughputMiBps(Long throughputMiBps) {
        this.throughputMiBps = throughputMiBps;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VolumeTier that = (VolumeTier) o;
        return Objects.equals(tier, that.tier) &&
                Objects.equals(sizeGiB, that.sizeGiB) &&
                Objects.equals(pricePerMonth, that.pricePerMonth) &&
                Objects.equals(iops, that.iops) &&
                Objects.equals(throughputMiBps, that.throughputMiBps);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier, sizeGiB, pricePerMonth, iops, throughputMiBps);
    }

    @Override
    public String toString() {
        return "VolumeTier[tier=" + tier +
                ", sizeGiB=" + sizeGiB +
                ", pricePerMonth=" + pricePerMonth +
                ", iops=" + iops +
                ", throughputMiBps=" + throughputMiBps +
                "]";
    }
}
