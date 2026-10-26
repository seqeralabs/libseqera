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
 * Decodes the cross-provider GPU facts served from cloudinfo 0.30.0
 * (seqeralabs/cloudinfo#130, #132). The fixture products-gpu-models.json holds
 * real prod products from every provider, with the new fields as the cloudinfo
 * branch computes them.
 */
class CloudProductGpuFieldsTest extends Specification {

    private static final JacksonEncodingStrategy<CloudResponse> RESPONSE =
            new JacksonEncodingStrategy<CloudResponse>() {}

    private static final JacksonEncodingStrategy<CloudProduct> PRODUCT =
            new JacksonEncodingStrategy<CloudProduct>() {}

    private static Map<String, CloudProduct> products() {
        def json = CloudProductGpuFieldsTest.getResourceAsStream('/cloudinfo/products-gpu-models.json').text
        RESPONSE.decode(json).products.collectEntries { [it.type, it] }
    }

    def 'decodes model, capability, CUDA range and memory of an NVIDIA GPU'() {
        when:
        def g5 = products()['g5.xlarge']

        then:
        g5.gpuModel == 'nvidia-a10g'
        g5.gpuVendor == 'nvidia'
        g5.gpuCapability == '8.6'
        g5.gpuArch == 'ampere'
        g5.gpuCudaMin == '11.1'
        g5.gpuCudaMax == null
        g5.gpuMemoryGB == 24L

        and: 'the provider-reported total is kept, distinct from the nominal size'
        g5.gpuMemoryMiB == 22888L
    }

    def 'a retired architecture carries both ends of its CUDA range and no memory'() {
        when:
        def p2 = products()['p2.xlarge']

        then:
        p2.gpuModel == 'nvidia-k80'
        p2.gpuArch == 'kepler'
        p2.gpuCudaMin == '6.5'
        p2.gpuCudaMax == '11.8'
        p2.gpuMemoryGB == null
        p2.gpuMemoryMiB == null
    }

    def 'a fractional azure GPU reports the whole card and its derived share'() {
        when:
        def nv = products()['Standard_NV6ads_A10_v5']

        then:
        nv.gpusPerVm > 0f && nv.gpusPerVm < 1f
        nv.gpuModel == 'nvidia-a10'
        nv.gpuMemoryGB == 24L
        nv.gpuMemoryMiB == 4096L
    }

    def 'the same model ID is served on every provider'() {
        when:
        def p = products()

        then:
        p['a3-highgpu-8g'].gpuModel == 'nvidia-h100'
        p['a3-highgpu-8g'].acceleratorType == 'nvidia-h100-80gb'
        p['a3-highgpu-8g'].gpuMemoryGB == 80L
        p['g5.xlarge'].gpuModel != p['Standard_NV6ads_A10_v5'].gpuModel // a10g is not a10
    }

    def 'AMD and undisclosed parts have a model and memory but no CUDA facts'() {
        when:
        def p = products()
        def amd = p['Standard_ND96isr_MI300X_v5']
        def masked = p['ecs.gn8v.4xlarge']

        then:
        amd.gpuModel == 'amd-mi300x'
        amd.gpuVendor == 'amd'
        amd.gpuMemoryGB == 192L
        [amd.gpuCapability, amd.gpuArch, amd.gpuCudaMin, amd.gpuCudaMax].every { it == null }

        and:
        masked.gpuModel == 'nvidia-gpu-h'
        masked.gpuMemoryGB == 96L
        masked.gpuCapability == null
    }

    def 'a CPU-only product and a pre-0.30.0 payload leave every GPU fact null'() {
        given:
        def legacy = PRODUCT.decode('{"type":"g5.xlarge","gpusPerVm":1,"features":["a10g","gpu","nvidia"]}')

        expect:
        [products()['m5.large'], legacy].every { CloudProduct p ->
            [p.gpuModel, p.gpuVendor, p.gpuCapability, p.gpuArch, p.gpuCudaMin, p.gpuCudaMax, p.gpuMemoryGB].every { it == null }
        }
    }

    def 'the GPU facts round-trip through Jackson and take part in equals and hashCode'() {
        given:
        def g5 = products()['g5.xlarge']
        def copy = PRODUCT.decode(PRODUCT.encode(g5))
        def other = PRODUCT.decode(PRODUCT.encode(g5))
        other.gpuCapability = '8.0'

        expect:
        copy == g5
        copy.hashCode() == g5.hashCode()
        other != g5
        g5.toString().contains('gpuModel=nvidia-a10g')
    }
}
