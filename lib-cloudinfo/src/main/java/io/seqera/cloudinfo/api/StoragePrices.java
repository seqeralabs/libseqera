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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Response model for the Cloudinfo /storage endpoint: the on-demand block-storage
 * (disk) prices of one region. Served by amazon, azure and google.
 *
 * @see io.seqera.cloudinfo.client.CloudInfoClient#getStoragePrices(String, String)
 */
public class StoragePrices {

    /** Pricing API the prices were read from (e.g. aws-pricing-api). */
    private String source;
    /**
     * When the prices were scraped, in Unix milliseconds (same encoding as the
     * products endpoint).
     */
    private String scrapingTime;
    /**
     * One entry per volume type, sorted by volume type. Never null: CloudInfo
     * sends {@code "volumes":null} for an empty region, which becomes an empty list.
     */
    private List<VolumePrice> volumes = new ArrayList<>();

    public StoragePrices() {
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getScrapingTime() {
        return scrapingTime;
    }

    public void setScrapingTime(String scrapingTime) {
        this.scrapingTime = scrapingTime;
    }

    public List<VolumePrice> getVolumes() {
        return volumes;
    }

    public void setVolumes(List<VolumePrice> volumes) {
        this.volumes = volumes != null ? volumes : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StoragePrices that = (StoragePrices) o;
        return Objects.equals(source, that.source) &&
                Objects.equals(scrapingTime, that.scrapingTime) &&
                Objects.equals(volumes, that.volumes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, scrapingTime, volumes);
    }

    @Override
    public String toString() {
        return "StoragePrices[source=" + source +
                ", scrapingTime=" + scrapingTime +
                ", volumes=" + volumes +
                "]";
    }
}
