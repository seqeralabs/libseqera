/*
 * Copyright 2025, Seqera Labs
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

    public CloudProduct() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CloudProduct that = (CloudProduct) o;
        return Objects.equals(type, that.type) &&
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
                Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, category, cpusPerVm, memPerVm, gpusPerVm, currentGen,
                ntwPerf, ntwPerfCategory, onDemandPrice, zones, spotPrice, attributes);
    }

    @Override
    public String toString() {
        return "CloudProduct[type=" + type +
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
                ", attributes=" + attributes + "]";
    }
}
