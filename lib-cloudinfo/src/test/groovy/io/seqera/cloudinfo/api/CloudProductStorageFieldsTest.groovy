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
package io.seqera.cloudinfo.api

import io.seqera.serde.jackson.JacksonEncodingStrategy
import spock.lang.Specification

/**
 * Decodes cloudinfo 0.25.0 products payloads carrying the per-instance-type
 * disk and GPU facts (seqeralabs/cloudinfo#95). The fixtures under
 * src/test/resources/cloudinfo follow the wire shape of cloudinfo's VMInfo
 * (omitempty fields left out), with values from its live-captured test data.
 */
class CloudProductStorageFieldsTest extends Specification {

    private static final JacksonEncodingStrategy<CloudResponse> RESPONSE =
            new JacksonEncodingStrategy<CloudResponse>() {}

    private static final JacksonEncodingStrategy<CloudProduct> PRODUCT =
            new JacksonEncodingStrategy<CloudProduct>() {}

    private static Map<String, CloudProduct> products(String fixture) {
        def json = CloudProductStorageFieldsTest.getResourceAsStream("/cloudinfo/${fixture}").text
        RESPONSE.decode(json).products.collectEntries { [it.type, it] }
    }

    def 'decodes amazon EBS, instance-store and GPU memory facts'() {
        when:
        def p = products('products-amazon-us-east-1.json')
        def m5 = p['m5.large']
        def g6 = p['g6.12xlarge']

        then:
        m5.ebsMaxBandwidthMbps == 4750L
        m5.ebsBaselineBandwidthMbps == 650L
        m5.ebsMaxThroughputMBps == 593.75d
        m5.ebsBaselineThroughputMBps == 81.25d
        m5.ebsMaxIops == 18750L
        m5.ebsBaselineIops == 3600L
        m5.instanceStoreGB == null
        m5.gpuMemoryMiB == null

        and:
        g6.ebsMaxBandwidthMbps == 20000L
        g6.ebsBaselineBandwidthMbps == 20000L
        g6.ebsMaxThroughputMBps == 2500d
        g6.ebsBaselineThroughputMBps == 2500d
        g6.ebsMaxIops == 80000L
        g6.ebsBaselineIops == 80000L
        g6.instanceStoreGB == 3760L
        g6.gpuMemoryMiB == 91552L
        g6.gpusPerVm == 4.0f

        and: 'no azure or google fields on an amazon product'
        g6.quotaFamily == null
        g6.supportedEphemeralOSDiskPlacements == null
        g6.bootDiskTypes == null
    }

    def 'decodes azure Resource SKU capabilities'() {
        when:
        def p = products('products-azure-eastus.json')
        def nc = p['Standard_NC24ads_A100_v4']
        def e8 = p['Standard_E8s_v6']

        then:
        nc.quotaFamily == 'StandardNCADSA100v4Family'
        nc.ephemeralOSDiskSupported
        nc.supportedEphemeralOSDiskPlacements == ['ResourceDisk', 'CacheDisk']
        nc.cachedDiskBytes == 274877906944L
        nc.nvmeDiskSizeInMiB == 915527L
        nc.maxResourceVolumeMB == 65536L
        nc.uncachedDiskIOPS == 30000L
        nc.uncachedDiskBytesPerSecond == 1024000000L
        nc.hyperVGenerations == ['V2']
        nc.diskControllerTypes == null
        nc.maxDataDiskCount == 8L
        nc.premiumIO
        nc.acceleratedNetworkingEnabled
        nc.maxNetworkInterfaces == 2L

        and: 'a false boolean is omitted by cloudinfo and decodes to false'
        !e8.ephemeralOSDiskSupported
        e8.supportedEphemeralOSDiskPlacements == null
        e8.diskControllerTypes == ['NVMe']
        e8.maxDataDiskCount == 24L
    }

    def 'decodes google machine-type disk and accelerator facts'() {
        when:
        def p = products('products-google-us-central1.json')
        def g2 = p['g2-standard-4']
        def c4 = p['c4-standard-8-lssd']
        def e2 = p['e2-micro']

        then:
        g2.acceleratorType == 'nvidia-l4'
        g2.gpuMemoryMiB == 24576L
        g2.attachableLocalSsdCounts == [1L]
        g2.attachableLocalSsdCounts[0] instanceof Long
        g2.maximumPersistentDisks == 128L
        g2.maximumPersistentDisksSizeGb == 263168L
        g2.bootDiskTypes == ['pd-balanced', 'pd-ssd']
        g2.localSsdPartitions == null
        !g2.isSharedCpu

        and:
        c4.localSsdPartitions == 1L
        c4.localSsdGiB == 375L
        c4.attachableLocalSsdCounts == null
        c4.maximumPersistentDisksSizeGb == 524288L
        c4.bootDiskTypes == ['hyperdisk-balanced', 'hyperdisk-balanced-high-availability']

        and:
        e2.isSharedCpu
    }

    def 'isSharedCpu keeps its wire name through a Jackson round-trip'() {
        given:
        def product = new CloudProduct(type: 'e2-micro', isSharedCpu: true)

        when:
        def json = PRODUCT.encode(product)

        then:
        json.contains('"isSharedCpu":true')
        !json.contains('"sharedCpu"')
        PRODUCT.decode(json).isSharedCpu
    }

    def 'a pre-0.25.0 product without any of the new fields still decodes'() {
        given:
        def json = '{"category":"General purpose","type":"m5.large","family":"m5","onDemandPrice":0.096,' +
                '"spotPrice":[{"zone":"us-east-1a","price":0.0544}],"cpusPerVm":2,"memPerVm":8,"gpusPerVm":0,' +
                '"ntwPerf":"Up to 10 Gigabit","ntwPerfCategory":"high","zones":null,' +
                '"attributes":{"cpu":"2","memory":"8"},"currentGen":true,"features":["sched","x86"]}'

        when:
        def p = PRODUCT.decode(json)

        then:
        p.type == 'm5.large'
        p.onDemandPrice == 0.096f
        p.features == ['sched', 'x86']
        [p.ebsMaxBandwidthMbps, p.ebsMaxThroughputMBps, p.instanceStoreGB, p.gpuMemoryMiB,
         p.quotaFamily, p.supportedEphemeralOSDiskPlacements,
         p.acceleratorType, p.localSsdGiB, p.attachableLocalSsdCounts, p.bootDiskTypes].every { it == null }
        !p.ephemeralOSDiskSupported
        !p.premiumIO
        !p.acceleratedNetworkingEnabled
        !p.isSharedCpu
    }

    def 'equals and hashCode include the new fields'() {
        given:
        def a = new CloudProduct(type: 'm5.large', ebsMaxBandwidthMbps: 4750L)
        def b = new CloudProduct(type: 'm5.large', ebsMaxBandwidthMbps: 4750L)
        def c = new CloudProduct(type: 'm5.large', ebsMaxBandwidthMbps: 650L)

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }
}
