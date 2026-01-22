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

import java.util.Objects;

/**
 * Model Cloudinfo product attributes
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ProductAttributes {

    private Integer cpu;
    private String memory;
    private String instanceTypeCategory;
    private String networkPerfCategory;

    public ProductAttributes() {
    }

    public ProductAttributes(Integer cpu, String memory, String instanceTypeCategory, String networkPerfCategory) {
        this.cpu = cpu;
        this.memory = memory;
        this.instanceTypeCategory = instanceTypeCategory;
        this.networkPerfCategory = networkPerfCategory;
    }

    public Integer getCpu() {
        return cpu;
    }

    public void setCpu(Integer cpu) {
        this.cpu = cpu;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getInstanceTypeCategory() {
        return instanceTypeCategory;
    }

    public void setInstanceTypeCategory(String instanceTypeCategory) {
        this.instanceTypeCategory = instanceTypeCategory;
    }

    public String getNetworkPerfCategory() {
        return networkPerfCategory;
    }

    public void setNetworkPerfCategory(String networkPerfCategory) {
        this.networkPerfCategory = networkPerfCategory;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProductAttributes that = (ProductAttributes) o;
        return Objects.equals(cpu, that.cpu) &&
                Objects.equals(memory, that.memory) &&
                Objects.equals(instanceTypeCategory, that.instanceTypeCategory) &&
                Objects.equals(networkPerfCategory, that.networkPerfCategory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cpu, memory, instanceTypeCategory, networkPerfCategory);
    }

    @Override
    public String toString() {
        return "ProductAttributes[cpu=" + cpu + ", memory=" + memory +
                ", instanceTypeCategory=" + instanceTypeCategory +
                ", networkPerfCategory=" + networkPerfCategory + "]";
    }
}
