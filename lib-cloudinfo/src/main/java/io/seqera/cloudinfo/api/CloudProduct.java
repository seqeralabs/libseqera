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
     * Machine family this instance type belongs to (e.g. m5d for m5d.large).
     * Used by the /families endpoint and the families query filter. Null when
     * the backend does not populate it (legacy responses).
     */
    private String family;
    private String category;
    private Integer cpusPerVm;
    private Float memPerVm;
    /**
     * Number of GPUs (accelerators) per instance, as reported by CloudInfo.
     * Modelled as a {@link Float} because fractional-GPU families report a
     * fraction of a physical accelerator (e.g. AWS {@code g6f.2xlarge} reports
     * {@code 0.25}); an integer field would truncate such values to {@code 0}
     * and misclassify the instance as CPU-only. A value {@code > 0} (including
     * a fraction) means the instance carries an accelerator. Null on legacy
     * responses that do not populate it.
     */
    private Float gpusPerVm;
    private Boolean currentGen;
    private String ntwPerf;
    private String ntwPerfCategory;
    private Float onDemandPrice;
    private List<String> zones;
    private List<CloudPrice> spotPrice;
    private ProductAttributes attributes;
    /**
     * Capability feature tokens for this instance type, as a flat list of
     * lowercase tokens: ssd, gpu, arm, x86, burst, hibernation, sched, plus GPU
     * vendor tokens (nvidia, amd, habana) and model tokens (a100, tesla-a100, ...).
     * These are also the accepted values of the features filter on ProductsQuery
     * and the /families endpoint. Consumers usually map them onto their own enum
     * and drop unrecognised tokens.
     *
     * Null means the backend did not populate features (legacy); an empty list
     * means none advertised.
     */
    private List<String> features;

    /*
     * Amazon-only facts from ec2:DescribeInstanceTypes; null when not reported (and on
     * backends older than cloudinfo 0.25.0).
     */
    /** Maximum (burst) EBS bandwidth of the instance, in Mbit/s. */
    private Long ebsMaxBandwidthMbps;
    /** Sustained EBS bandwidth of the instance, in Mbit/s. */
    private Long ebsBaselineBandwidthMbps;
    /** Maximum (burst) EBS throughput of the instance, in MB/s. */
    private Double ebsMaxThroughputMBps;
    /** Sustained EBS throughput of the instance, in MB/s. */
    private Double ebsBaselineThroughputMBps;
    /** Maximum (burst) EBS IOPS of the instance (16 KiB I/O). */
    private Long ebsMaxIops;
    /** Sustained EBS IOPS of the instance (16 KiB I/O). */
    private Long ebsBaselineIops;
    /** Total size of the bundled instance-store disks, in GB. */
    private Long instanceStoreGB;
    /** Total GPU memory across every GPU of the instance, in MiB (amazon and google). */
    private Long gpuMemoryMiB;

    /*
     * Azure-only Resource SKU capabilities, under Azure's names and units; null when the
     * SKU does not report them.
     */
    /**
     * Raw SKU family (e.g. standardDSv5Family) that vCPU quotas are counted against;
     * {@link #family} holds the normalised form.
     */
    private String quotaFamily;
    private Boolean ephemeralOSDiskSupported;
    /** Local disks the ephemeral OS disk can be placed on (ResourceDisk, CacheDisk, NvmeDisk). */
    private List<String> supportedEphemeralOSDiskPlacements;
    /** Size of the host cache disk, in bytes. */
    private Long cachedDiskBytes;
    /** Total size of the local NVMe disks, in MiB. */
    private Long nvmeDiskSizeInMiB;
    /** Size of the SCSI resource (temp) disk, in MB. */
    private Long maxResourceVolumeMB;
    /** VM limit on remote managed-disk IOPS. */
    private Long uncachedDiskIOPS;
    /** VM limit on remote managed-disk throughput, in (decimal) bytes per second. */
    private Long uncachedDiskBytesPerSecond;
    /** VM generations the size boots (V1, V2). */
    private List<String> hyperVGenerations;
    /** Disk controllers the size supports (SCSI, NVMe). */
    private List<String> diskControllerTypes;
    /** Maximum number of attached data disks. */
    private Long maxDataDiskCount;
    private Boolean premiumIO;
    private Boolean acceleratedNetworkingEnabled;
    /** Maximum number of network interfaces. */
    private Long maxNetworkInterfaces;

    /*
     * Google-only facts from the machineTypes API (or curated where noted); null when
     * not reported.
     */
    /** Bundled accelerator type (e.g. nvidia-l4). */
    private String acceleratorType;
    /** Number of bundled local SSD disks (bundledLocalSsds.partitionCount). */
    private Long localSsdPartitions;
    /** Total size of the bundled local SSD disks, in GiB. */
    private Long localSsdGiB;
    /**
     * Valid numbers of 375 GiB local SSD disks attachable at launch (curated); null
     * when the type bundles its local SSD or accepts none.
     */
    private List<Long> attachableLocalSsdCounts;
    /** Maximum number of attached persistent disks. */
    private Long maximumPersistentDisks;
    /** Maximum total size of attached persistent disks, in GB. */
    private Long maximumPersistentDisksSizeGb;
    private Boolean isSharedCpu;
    /** Disk types the machine type accepts as boot disk (curated). */
    private List<String> bootDiskTypes;

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

    public Float getGpusPerVm() {
        return gpusPerVm;
    }

    public void setGpusPerVm(Float gpusPerVm) {
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

    public Long getEbsMaxBandwidthMbps() {
        return ebsMaxBandwidthMbps;
    }

    public void setEbsMaxBandwidthMbps(Long ebsMaxBandwidthMbps) {
        this.ebsMaxBandwidthMbps = ebsMaxBandwidthMbps;
    }

    public Long getEbsBaselineBandwidthMbps() {
        return ebsBaselineBandwidthMbps;
    }

    public void setEbsBaselineBandwidthMbps(Long ebsBaselineBandwidthMbps) {
        this.ebsBaselineBandwidthMbps = ebsBaselineBandwidthMbps;
    }

    public Double getEbsMaxThroughputMBps() {
        return ebsMaxThroughputMBps;
    }

    public void setEbsMaxThroughputMBps(Double ebsMaxThroughputMBps) {
        this.ebsMaxThroughputMBps = ebsMaxThroughputMBps;
    }

    public Double getEbsBaselineThroughputMBps() {
        return ebsBaselineThroughputMBps;
    }

    public void setEbsBaselineThroughputMBps(Double ebsBaselineThroughputMBps) {
        this.ebsBaselineThroughputMBps = ebsBaselineThroughputMBps;
    }

    public Long getEbsMaxIops() {
        return ebsMaxIops;
    }

    public void setEbsMaxIops(Long ebsMaxIops) {
        this.ebsMaxIops = ebsMaxIops;
    }

    public Long getEbsBaselineIops() {
        return ebsBaselineIops;
    }

    public void setEbsBaselineIops(Long ebsBaselineIops) {
        this.ebsBaselineIops = ebsBaselineIops;
    }

    public Long getInstanceStoreGB() {
        return instanceStoreGB;
    }

    public void setInstanceStoreGB(Long instanceStoreGB) {
        this.instanceStoreGB = instanceStoreGB;
    }

    public Long getGpuMemoryMiB() {
        return gpuMemoryMiB;
    }

    public void setGpuMemoryMiB(Long gpuMemoryMiB) {
        this.gpuMemoryMiB = gpuMemoryMiB;
    }

    public String getQuotaFamily() {
        return quotaFamily;
    }

    public void setQuotaFamily(String quotaFamily) {
        this.quotaFamily = quotaFamily;
    }

    /**
     * Azure-only. Whether the OS disk can be ephemeral.
     *
     * <p>CloudInfo omits this field when it is false, so {@code null} means
     * "false or not reported"; treat only {@code Boolean.TRUE} as true.
     */
    public Boolean getEphemeralOSDiskSupported() {
        return ephemeralOSDiskSupported;
    }

    public void setEphemeralOSDiskSupported(Boolean ephemeralOSDiskSupported) {
        this.ephemeralOSDiskSupported = ephemeralOSDiskSupported;
    }

    public List<String> getSupportedEphemeralOSDiskPlacements() {
        return supportedEphemeralOSDiskPlacements;
    }

    public void setSupportedEphemeralOSDiskPlacements(List<String> supportedEphemeralOSDiskPlacements) {
        this.supportedEphemeralOSDiskPlacements = supportedEphemeralOSDiskPlacements;
    }

    public Long getCachedDiskBytes() {
        return cachedDiskBytes;
    }

    public void setCachedDiskBytes(Long cachedDiskBytes) {
        this.cachedDiskBytes = cachedDiskBytes;
    }

    public Long getNvmeDiskSizeInMiB() {
        return nvmeDiskSizeInMiB;
    }

    public void setNvmeDiskSizeInMiB(Long nvmeDiskSizeInMiB) {
        this.nvmeDiskSizeInMiB = nvmeDiskSizeInMiB;
    }

    public Long getMaxResourceVolumeMB() {
        return maxResourceVolumeMB;
    }

    public void setMaxResourceVolumeMB(Long maxResourceVolumeMB) {
        this.maxResourceVolumeMB = maxResourceVolumeMB;
    }

    public Long getUncachedDiskIOPS() {
        return uncachedDiskIOPS;
    }

    public void setUncachedDiskIOPS(Long uncachedDiskIOPS) {
        this.uncachedDiskIOPS = uncachedDiskIOPS;
    }

    public Long getUncachedDiskBytesPerSecond() {
        return uncachedDiskBytesPerSecond;
    }

    public void setUncachedDiskBytesPerSecond(Long uncachedDiskBytesPerSecond) {
        this.uncachedDiskBytesPerSecond = uncachedDiskBytesPerSecond;
    }

    public List<String> getHyperVGenerations() {
        return hyperVGenerations;
    }

    public void setHyperVGenerations(List<String> hyperVGenerations) {
        this.hyperVGenerations = hyperVGenerations;
    }

    public List<String> getDiskControllerTypes() {
        return diskControllerTypes;
    }

    public void setDiskControllerTypes(List<String> diskControllerTypes) {
        this.diskControllerTypes = diskControllerTypes;
    }

    public Long getMaxDataDiskCount() {
        return maxDataDiskCount;
    }

    public void setMaxDataDiskCount(Long maxDataDiskCount) {
        this.maxDataDiskCount = maxDataDiskCount;
    }

    /**
     * Azure-only. Whether the size accepts Premium SSD managed disks.
     *
     * <p>CloudInfo omits this field when it is false, so {@code null} means
     * "false or not reported"; treat only {@code Boolean.TRUE} as true.
     */
    public Boolean getPremiumIO() {
        return premiumIO;
    }

    public void setPremiumIO(Boolean premiumIO) {
        this.premiumIO = premiumIO;
    }

    /**
     * Azure-only. Whether the size supports accelerated networking.
     *
     * <p>CloudInfo omits this field when it is false, so {@code null} means
     * "false or not reported"; treat only {@code Boolean.TRUE} as true.
     */
    public Boolean getAcceleratedNetworkingEnabled() {
        return acceleratedNetworkingEnabled;
    }

    public void setAcceleratedNetworkingEnabled(Boolean acceleratedNetworkingEnabled) {
        this.acceleratedNetworkingEnabled = acceleratedNetworkingEnabled;
    }

    public Long getMaxNetworkInterfaces() {
        return maxNetworkInterfaces;
    }

    public void setMaxNetworkInterfaces(Long maxNetworkInterfaces) {
        this.maxNetworkInterfaces = maxNetworkInterfaces;
    }

    public String getAcceleratorType() {
        return acceleratorType;
    }

    public void setAcceleratorType(String acceleratorType) {
        this.acceleratorType = acceleratorType;
    }

    public Long getLocalSsdPartitions() {
        return localSsdPartitions;
    }

    public void setLocalSsdPartitions(Long localSsdPartitions) {
        this.localSsdPartitions = localSsdPartitions;
    }

    public Long getLocalSsdGiB() {
        return localSsdGiB;
    }

    public void setLocalSsdGiB(Long localSsdGiB) {
        this.localSsdGiB = localSsdGiB;
    }

    public List<Long> getAttachableLocalSsdCounts() {
        return attachableLocalSsdCounts;
    }

    public void setAttachableLocalSsdCounts(List<Long> attachableLocalSsdCounts) {
        this.attachableLocalSsdCounts = attachableLocalSsdCounts;
    }

    public Long getMaximumPersistentDisks() {
        return maximumPersistentDisks;
    }

    public void setMaximumPersistentDisks(Long maximumPersistentDisks) {
        this.maximumPersistentDisks = maximumPersistentDisks;
    }

    public Long getMaximumPersistentDisksSizeGb() {
        return maximumPersistentDisksSizeGb;
    }

    public void setMaximumPersistentDisksSizeGb(Long maximumPersistentDisksSizeGb) {
        this.maximumPersistentDisksSizeGb = maximumPersistentDisksSizeGb;
    }

    /**
     * Google-only. Whether this is a shared-core type (e2-micro, f1-micro, ...).
     *
     * <p>CloudInfo omits this field when it is false, so {@code null} means
     * "false or not reported"; treat only {@code Boolean.TRUE} as true.
     */
    public Boolean getIsSharedCpu() {
        return isSharedCpu;
    }

    public void setIsSharedCpu(Boolean isSharedCpu) {
        this.isSharedCpu = isSharedCpu;
    }

    public List<String> getBootDiskTypes() {
        return bootDiskTypes;
    }

    public void setBootDiskTypes(List<String> bootDiskTypes) {
        this.bootDiskTypes = bootDiskTypes;
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
                Objects.equals(features, that.features) &&
                Objects.equals(ebsMaxBandwidthMbps, that.ebsMaxBandwidthMbps) &&
                Objects.equals(ebsBaselineBandwidthMbps, that.ebsBaselineBandwidthMbps) &&
                Objects.equals(ebsMaxThroughputMBps, that.ebsMaxThroughputMBps) &&
                Objects.equals(ebsBaselineThroughputMBps, that.ebsBaselineThroughputMBps) &&
                Objects.equals(ebsMaxIops, that.ebsMaxIops) &&
                Objects.equals(ebsBaselineIops, that.ebsBaselineIops) &&
                Objects.equals(instanceStoreGB, that.instanceStoreGB) &&
                Objects.equals(gpuMemoryMiB, that.gpuMemoryMiB) &&
                Objects.equals(quotaFamily, that.quotaFamily) &&
                Objects.equals(ephemeralOSDiskSupported, that.ephemeralOSDiskSupported) &&
                Objects.equals(supportedEphemeralOSDiskPlacements, that.supportedEphemeralOSDiskPlacements) &&
                Objects.equals(cachedDiskBytes, that.cachedDiskBytes) &&
                Objects.equals(nvmeDiskSizeInMiB, that.nvmeDiskSizeInMiB) &&
                Objects.equals(maxResourceVolumeMB, that.maxResourceVolumeMB) &&
                Objects.equals(uncachedDiskIOPS, that.uncachedDiskIOPS) &&
                Objects.equals(uncachedDiskBytesPerSecond, that.uncachedDiskBytesPerSecond) &&
                Objects.equals(hyperVGenerations, that.hyperVGenerations) &&
                Objects.equals(diskControllerTypes, that.diskControllerTypes) &&
                Objects.equals(maxDataDiskCount, that.maxDataDiskCount) &&
                Objects.equals(premiumIO, that.premiumIO) &&
                Objects.equals(acceleratedNetworkingEnabled, that.acceleratedNetworkingEnabled) &&
                Objects.equals(maxNetworkInterfaces, that.maxNetworkInterfaces) &&
                Objects.equals(acceleratorType, that.acceleratorType) &&
                Objects.equals(localSsdPartitions, that.localSsdPartitions) &&
                Objects.equals(localSsdGiB, that.localSsdGiB) &&
                Objects.equals(attachableLocalSsdCounts, that.attachableLocalSsdCounts) &&
                Objects.equals(maximumPersistentDisks, that.maximumPersistentDisks) &&
                Objects.equals(maximumPersistentDisksSizeGb, that.maximumPersistentDisksSizeGb) &&
                Objects.equals(isSharedCpu, that.isSharedCpu) &&
                Objects.equals(bootDiskTypes, that.bootDiskTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, family, category, cpusPerVm, memPerVm, gpusPerVm, currentGen,
                ntwPerf, ntwPerfCategory, onDemandPrice, zones, spotPrice, attributes, features,
                ebsMaxBandwidthMbps, ebsBaselineBandwidthMbps, ebsMaxThroughputMBps,
                ebsBaselineThroughputMBps, ebsMaxIops, ebsBaselineIops, instanceStoreGB, gpuMemoryMiB,
                quotaFamily, ephemeralOSDiskSupported, supportedEphemeralOSDiskPlacements, cachedDiskBytes,
                nvmeDiskSizeInMiB, maxResourceVolumeMB, uncachedDiskIOPS, uncachedDiskBytesPerSecond,
                hyperVGenerations, diskControllerTypes, maxDataDiskCount, premiumIO,
                acceleratedNetworkingEnabled, maxNetworkInterfaces, acceleratorType, localSsdPartitions,
                localSsdGiB, attachableLocalSsdCounts, maximumPersistentDisks, maximumPersistentDisksSizeGb,
                isSharedCpu, bootDiskTypes);
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
                ", features=" + features +
                ", ebsMaxBandwidthMbps=" + ebsMaxBandwidthMbps +
                ", ebsBaselineBandwidthMbps=" + ebsBaselineBandwidthMbps +
                ", ebsMaxThroughputMBps=" + ebsMaxThroughputMBps +
                ", ebsBaselineThroughputMBps=" + ebsBaselineThroughputMBps +
                ", ebsMaxIops=" + ebsMaxIops +
                ", ebsBaselineIops=" + ebsBaselineIops +
                ", instanceStoreGB=" + instanceStoreGB +
                ", gpuMemoryMiB=" + gpuMemoryMiB +
                ", quotaFamily=" + quotaFamily +
                ", ephemeralOSDiskSupported=" + ephemeralOSDiskSupported +
                ", supportedEphemeralOSDiskPlacements=" + supportedEphemeralOSDiskPlacements +
                ", cachedDiskBytes=" + cachedDiskBytes +
                ", nvmeDiskSizeInMiB=" + nvmeDiskSizeInMiB +
                ", maxResourceVolumeMB=" + maxResourceVolumeMB +
                ", uncachedDiskIOPS=" + uncachedDiskIOPS +
                ", uncachedDiskBytesPerSecond=" + uncachedDiskBytesPerSecond +
                ", hyperVGenerations=" + hyperVGenerations +
                ", diskControllerTypes=" + diskControllerTypes +
                ", maxDataDiskCount=" + maxDataDiskCount +
                ", premiumIO=" + premiumIO +
                ", acceleratedNetworkingEnabled=" + acceleratedNetworkingEnabled +
                ", maxNetworkInterfaces=" + maxNetworkInterfaces +
                ", acceleratorType=" + acceleratorType +
                ", localSsdPartitions=" + localSsdPartitions +
                ", localSsdGiB=" + localSsdGiB +
                ", attachableLocalSsdCounts=" + attachableLocalSsdCounts +
                ", maximumPersistentDisks=" + maximumPersistentDisks +
                ", maximumPersistentDisksSizeGb=" + maximumPersistentDisksSizeGb +
                ", isSharedCpu=" + isSharedCpu +
                ", bootDiskTypes=" + bootDiskTypes +
                "]";
    }
}
