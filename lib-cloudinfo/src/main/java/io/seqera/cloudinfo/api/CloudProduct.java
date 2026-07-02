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
 * Model Cloudinfo product
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CloudProduct {

    private String type;
    /**
     * Machine family this instance type belongs to — the provider-specific
     * grouping above individual sizes (e.g. {@code m5d} for {@code m5d.large},
     * {@code n2} for {@code n2-highcpu-32}, {@code DSv3} for
     * {@code Standard_D2s_v3}). Used by the CloudInfo {@code /families} endpoint
     * and the {@code families} query filter.
     *
     * <p>{@code null} when the backend does not populate it — a legacy response
     * predating the field, or a provider whose adapter does not set it (the
     * service then falls back to the dot-prefix of {@link #type} server-side).
     */
    private String family;
    private String category;
    private Integer cpusPerVm;
    private Float memPerVm;
    private Integer gpusPerVm;
    private Boolean currentGen;
    private String ntwPerf;
    private String ntwPerfCategory;
    private Float onDemandPrice;
    private List<String> zones;
    private List<CloudPrice> spotPrice;
    private ProductAttributes attributes;
    /**
     * Capability feature tokens advertised for this instance type by the CloudInfo
     * backend, as a flat, additive array of <b>lowercase</b> tokens. The vocabulary
     * (as of cloudinfo PR&nbsp;#61) is:
     *
     * <ul>
     *   <li>Tier-1 capabilities derived from the cloud-provider APIs:
     *       {@code ssd}, {@code gpu}, {@code arm}, {@code x86}, {@code burst},
     *       {@code hibernation}.</li>
     *   <li>{@code sched} — Seqera Scheduler eligibility (curated; AWS only).</li>
     *   <li>For accelerated instance types, GPU vendor tokens
     *       ({@code nvidia}, {@code amd}, {@code habana}) and open-ended GPU model
     *       tokens (e.g. {@code a100}, {@code tesla-a100}, {@code radeon-pro-v520},
     *       {@code gaudi-hl-205}) — emitted by AWS and GCP; Azure reports only
     *       {@code gpu}.</li>
     * </ul>
     *
     * <p>The local-storage capability is the provider-neutral {@code ssd} (on AWS it
     * is presented over NVMe, hence the legacy {@code nvme} filter alias). Consumers
     * typically map these tokens onto a domain-specific enum (such as the platform's
     * {@code Feature} enum on {@code InstanceType}) and silently drop unrecognised
     * ones. The same tokens are the accepted values of the {@code features} filter on
     * {@link ProductsQuery} and of the CloudInfo {@code /families} endpoint.
     *
     * <p>{@code null} means the data source did not populate features (a legacy
     * backend that predates the field). Current backends always emit a
     * (possibly empty) array; an empty list explicitly means "no features advertised".
     */
    private List<String> features;

    public CloudProduct() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getCpusPerVm() {
        return cpusPerVm;
    }

    public void setCpusPerVm(Integer cpusPerVm) {
        this.cpusPerVm = cpusPerVm;
    }

    public Float getMemPerVm() {
        return memPerVm;
    }

    public void setMemPerVm(Float memPerVm) {
        this.memPerVm = memPerVm;
    }

    public Integer getGpusPerVm() {
        return gpusPerVm;
    }

    public void setGpusPerVm(Integer gpusPerVm) {
        this.gpusPerVm = gpusPerVm;
    }

    public Boolean getCurrentGen() {
        return currentGen;
    }

    public void setCurrentGen(Boolean currentGen) {
        this.currentGen = currentGen;
    }

    public String getNtwPerf() {
        return ntwPerf;
    }

    public void setNtwPerf(String ntwPerf) {
        this.ntwPerf = ntwPerf;
    }

    public String getNtwPerfCategory() {
        return ntwPerfCategory;
    }

    public void setNtwPerfCategory(String ntwPerfCategory) {
        this.ntwPerfCategory = ntwPerfCategory;
    }

    public Float getOnDemandPrice() {
        return onDemandPrice;
    }

    public void setOnDemandPrice(Float onDemandPrice) {
        this.onDemandPrice = onDemandPrice;
    }

    public List<String> getZones() {
        return zones;
    }

    public void setZones(List<String> zones) {
        this.zones = zones;
    }

    public List<CloudPrice> getSpotPrice() {
        return spotPrice;
    }

    public void setSpotPrice(List<CloudPrice> spotPrice) {
        this.spotPrice = spotPrice;
    }

    public ProductAttributes getAttributes() {
        return attributes;
    }

    public void setAttributes(ProductAttributes attributes) {
        this.attributes = attributes;
    }

    public List<String> getFeatures() {
        return features;
    }

    public void setFeatures(List<String> features) {
        this.features = features;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CloudProduct that = (CloudProduct) o;
        return Objects.equals(type, that.type) &&
                Objects.equals(family, that.family) &&
                Objects.equals(category, that.category) &&
                Objects.equals(cpusPerVm, that.cpusPerVm) &&
                Objects.equals(memPerVm, that.memPerVm) &&
                Objects.equals(gpusPerVm, that.gpusPerVm) &&
                Objects.equals(currentGen, that.currentGen) &&
                Objects.equals(ntwPerf, that.ntwPerf) &&
                Objects.equals(ntwPerfCategory, that.ntwPerfCategory) &&
                Objects.equals(onDemandPrice, that.onDemandPrice) &&
                Objects.equals(zones, that.zones) &&
                Objects.equals(spotPrice, that.spotPrice) &&
                Objects.equals(attributes, that.attributes) &&
                Objects.equals(features, that.features);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, family, category, cpusPerVm, memPerVm, gpusPerVm, currentGen,
                ntwPerf, ntwPerfCategory, onDemandPrice, zones, spotPrice, attributes, features);
    }

    @Override
    public String toString() {
        return "CloudProduct[type=" + type +
                ", family=" + family +
                ", category=" + category +
                ", cpusPerVm=" + cpusPerVm +
                ", memPerVm=" + memPerVm +
                ", gpusPerVm=" + gpusPerVm +
                ", currentGen=" + currentGen +
                ", ntwPerf=" + ntwPerf +
                ", ntwPerfCategory=" + ntwPerfCategory +
                ", onDemandPrice=" + onDemandPrice +
                ", zones=" + zones +
                ", spotPrice=" + spotPrice +
                ", attributes=" + attributes +
                ", features=" + features + "]";
    }
}
