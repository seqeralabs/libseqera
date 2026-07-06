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

/**
 * Provider-agnostic helpers over the accelerator-related CloudProduct.features tokens. The token
 * vocabulary is shared across AWS/GCP/Azure, so classification lives with the model instead of
 * being duplicated by each consumer. CloudInfo advertises an accelerator with a type token (gpu,
 * fpga), a vendor token (e.g. nvidia, xilinx) and a model token (e.g. a100, virtex-ultrascale-vu47p).
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public final class AcceleratorFeatures {

    /** Capability token marking a GPU-bearing instance type. */
    public static final String TOKEN_GPU = "gpu";

    /** GPU vendor tokens detected from CloudInfo features. */
    public static final String VENDOR_NVIDIA = "nvidia";
    public static final String VENDOR_AMD = "amd";
    public static final String VENDOR_HABANA = "habana";

    /** Capability token marking an FPGA-bearing instance type. */
    public static final String TOKEN_FPGA = "fpga";

    /** FPGA vendor tokens detected from CloudInfo features. */
    public static final String VENDOR_XILINX = "xilinx";

    private AcceleratorFeatures() {}

    /** True when the feature tokens mark this instance type as GPU-bearing. */
    public static boolean hasGpu(List<String> features) {
        return contains(features, TOKEN_GPU);
    }

    /** True when the feature tokens advertise an NVIDIA GPU. */
    public static boolean isNvidia(List<String> features) {
        return contains(features, VENDOR_NVIDIA);
    }

    /** True when the feature tokens mark this instance type as FPGA-bearing. */
    public static boolean hasFpga(List<String> features) {
        return contains(features, TOKEN_FPGA);
    }

    private static boolean contains(List<String> features, String token) {
        if (features == null) {
            return false;
        }
        for (String f : features) {
            if (f != null && f.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }
}
