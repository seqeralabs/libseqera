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
import java.util.Map;
import java.util.Objects;

/**
 * On-demand price of one block-storage volume type in a region.
 *
 * <p>Capacity, IOPS and throughput are priced separately because they do not scale
 * together across regions. A component that is not billed for the volume type, or
 * that CloudInfo cannot reduce to a single rate (e.g. amazon io2 provisioned IOPS),
 * is {@code null} rather than 0. The same applies to the free baselines and limits.
 */
public class VolumePrice {

    /** Provider API name of the volume type (amazon gp3, azure Premium_LRS, google pd-ssd, ...). */
    private String volumeType;
    /** Provider description of the backing media (e.g. SSD-backed). */
    private String storageMedia;
    /** Provisioned capacity, in USD per GB-month (binary GiB on all three providers). */
    private Double pricePerGBMonth;
    /** Provisioned IOPS above {@link #includedIops}, in USD per IOPS-month. */
    private Double pricePerIopsMonth;
    /** Provisioned throughput above {@link #includedThroughputMiBps}, in USD per MiB/s-month. */
    private Double pricePerMiBpsMonth;
    /** Provisioned IOPS included free with every volume (e.g. 3000 on amazon gp3). */
    private Long includedIops;
    /** Provisioned throughput included free with every volume, in MiB/s (e.g. 125 on amazon gp3). */
    private Long includedThroughputMiBps;
    /** Per-volume IOPS limit; null when published as a range. */
    private Long maxIops;
    /** Per-volume throughput limit, in MiB/s; null when published as a range. */
    private Long maxThroughputMiBps;
    /** Per-volume size limit, in GiB. */
    private Long maxSizeGiB;
    /**
     * Fixed-size tiers of a volume type billed per disk rather than per GB (azure
     * Standard HDD / Standard SSD / Premium SSD), sorted by size. A provisioned size
     * is billed as the smallest tier that fits it.
     */
    private List<VolumeTier> tiers;
    /**
     * Price of 10,000 disk transactions, for volume types that bill I/O operations
     * (azure Standard HDD / SSD).
     */
    private Double pricePer10kTransactions;
    /**
     * Per-machine-family overrides of {@link #pricePerGBMonth} (google local-ssd),
     * keyed by family or family variant (e.g. c4, z4d-highmem-highlssd).
     */
    private Map<String, Double> familyPricesPerGBMonth;

    public VolumePrice() {
    }

    public String getVolumeType() {
        return volumeType;
    }

    public void setVolumeType(String volumeType) {
        this.volumeType = volumeType;
    }

    public String getStorageMedia() {
        return storageMedia;
    }

    public void setStorageMedia(String storageMedia) {
        this.storageMedia = storageMedia;
    }

    public Double getPricePerGBMonth() {
        return pricePerGBMonth;
    }

    public void setPricePerGBMonth(Double pricePerGBMonth) {
        this.pricePerGBMonth = pricePerGBMonth;
    }

    public Double getPricePerIopsMonth() {
        return pricePerIopsMonth;
    }

    public void setPricePerIopsMonth(Double pricePerIopsMonth) {
        this.pricePerIopsMonth = pricePerIopsMonth;
    }

    public Double getPricePerMiBpsMonth() {
        return pricePerMiBpsMonth;
    }

    public void setPricePerMiBpsMonth(Double pricePerMiBpsMonth) {
        this.pricePerMiBpsMonth = pricePerMiBpsMonth;
    }

    public Long getIncludedIops() {
        return includedIops;
    }

    public void setIncludedIops(Long includedIops) {
        this.includedIops = includedIops;
    }

    public Long getIncludedThroughputMiBps() {
        return includedThroughputMiBps;
    }

    public void setIncludedThroughputMiBps(Long includedThroughputMiBps) {
        this.includedThroughputMiBps = includedThroughputMiBps;
    }

    public Long getMaxIops() {
        return maxIops;
    }

    public void setMaxIops(Long maxIops) {
        this.maxIops = maxIops;
    }

    public Long getMaxThroughputMiBps() {
        return maxThroughputMiBps;
    }

    public void setMaxThroughputMiBps(Long maxThroughputMiBps) {
        this.maxThroughputMiBps = maxThroughputMiBps;
    }

    public Long getMaxSizeGiB() {
        return maxSizeGiB;
    }

    public void setMaxSizeGiB(Long maxSizeGiB) {
        this.maxSizeGiB = maxSizeGiB;
    }

    public List<VolumeTier> getTiers() {
        return tiers;
    }

    public void setTiers(List<VolumeTier> tiers) {
        this.tiers = tiers;
    }

    public Double getPricePer10kTransactions() {
        return pricePer10kTransactions;
    }

    public void setPricePer10kTransactions(Double pricePer10kTransactions) {
        this.pricePer10kTransactions = pricePer10kTransactions;
    }

    public Map<String, Double> getFamilyPricesPerGBMonth() {
        return familyPricesPerGBMonth;
    }

    public void setFamilyPricesPerGBMonth(Map<String, Double> familyPricesPerGBMonth) {
        this.familyPricesPerGBMonth = familyPricesPerGBMonth;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VolumePrice that = (VolumePrice) o;
        return Objects.equals(volumeType, that.volumeType) &&
                Objects.equals(storageMedia, that.storageMedia) &&
                Objects.equals(pricePerGBMonth, that.pricePerGBMonth) &&
                Objects.equals(pricePerIopsMonth, that.pricePerIopsMonth) &&
                Objects.equals(pricePerMiBpsMonth, that.pricePerMiBpsMonth) &&
                Objects.equals(includedIops, that.includedIops) &&
                Objects.equals(includedThroughputMiBps, that.includedThroughputMiBps) &&
                Objects.equals(maxIops, that.maxIops) &&
                Objects.equals(maxThroughputMiBps, that.maxThroughputMiBps) &&
                Objects.equals(maxSizeGiB, that.maxSizeGiB) &&
                Objects.equals(tiers, that.tiers) &&
                Objects.equals(pricePer10kTransactions, that.pricePer10kTransactions) &&
                Objects.equals(familyPricesPerGBMonth, that.familyPricesPerGBMonth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(volumeType, storageMedia, pricePerGBMonth, pricePerIopsMonth,
                pricePerMiBpsMonth, includedIops, includedThroughputMiBps, maxIops,
                maxThroughputMiBps, maxSizeGiB, tiers, pricePer10kTransactions,
                familyPricesPerGBMonth);
    }

    @Override
    public String toString() {
        return "VolumePrice[volumeType=" + volumeType +
                ", storageMedia=" + storageMedia +
                ", pricePerGBMonth=" + pricePerGBMonth +
                ", pricePerIopsMonth=" + pricePerIopsMonth +
                ", pricePerMiBpsMonth=" + pricePerMiBpsMonth +
                ", includedIops=" + includedIops +
                ", includedThroughputMiBps=" + includedThroughputMiBps +
                ", maxIops=" + maxIops +
                ", maxThroughputMiBps=" + maxThroughputMiBps +
                ", maxSizeGiB=" + maxSizeGiB +
                ", tiers=" + tiers +
                ", pricePer10kTransactions=" + pricePer10kTransactions +
                ", familyPricesPerGBMonth=" + familyPricesPerGBMonth +
                "]";
    }
}
