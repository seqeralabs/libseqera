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

import spock.lang.Specification

class AcceleratorFeaturesTest extends Specification {

    def 'hasGpu detects the gpu token case-insensitively'() {
        expect:
        AcceleratorFeatures.hasGpu(features) == expected

        where:
        features                  | expected
        null                      | false
        []                        | false
        ['ssd', 'x86']            | false
        ['gpu', 'nvidia']         | true
        ['GPU']                   | true
        [null, 'gpu']             | true
    }

    def 'isNvidia detects the nvidia vendor token case-insensitively'() {
        expect:
        AcceleratorFeatures.isNvidia(features) == expected

        where:
        features                  | expected
        null                      | false
        ['gpu', 'amd']            | false
        ['gpu', 'nvidia']         | true
        ['gpu', 'NVIDIA']         | true
    }

    def 'hasFpga detects the fpga token case-insensitively'() {
        expect:
        AcceleratorFeatures.hasFpga(features) == expected

        where:
        features                                                       | expected
        null                                                           | false
        ['ssd']                                                        | false
        ['gpu', 'nvidia']                                              | false
        ['fpga', 'ssd', 'virtex-ultrascale-vu47p', 'x86', 'xilinx']    | true
        ['FPGA']                                                       | true
    }
}
