/*
 * Copyright 2013-2025, Seqera Labs
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

package io.seqera.sched.api.v1a1;

import java.util.Objects;

public class TerminateClusterRequest {
    public String region;
    public String clusterId;

    public TerminateClusterRequest withRegion(String region) {
        this.region = region;
        return this;
    }

    public TerminateClusterRequest withClusterId(String clusterId) {
        this.clusterId = clusterId;
        return this;
    }


    @Override
    public String toString() {
        return "TerminateClusterRequest {" +
                "region=" + region +
                ", clusterId=" + clusterId +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TerminateClusterRequest that = (TerminateClusterRequest) o;
        return Objects.equals(region, that.region)
                && Objects.equals(clusterId, that.clusterId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(region, clusterId);
    }
}
