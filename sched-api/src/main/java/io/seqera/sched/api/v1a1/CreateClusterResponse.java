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

public class CreateClusterResponse {

    public String clusterId;
    public String masterInstanceId;
    public String masterIp;
    public String masterDns;
    public String error;

    public CreateClusterResponse withClusterId(String clusterId) {
        this.clusterId = clusterId;
        return this;
    }

    public CreateClusterResponse withMasterInstanceId(String masterInstanceId) {
        this.masterInstanceId = masterInstanceId;
        return this;
    }

    public CreateClusterResponse withMasterIp(String masterIp) {
        this.masterIp = masterIp;
        return this;
    }

    public CreateClusterResponse withMasterDns(String masterDns) {
        this.masterDns = masterDns;
        return this;
    }

    public CreateClusterResponse withError(String error) {
        this.error = error;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CreateClusterResponse that = (CreateClusterResponse) o;
        return Objects.equals(clusterId, that.clusterId) &&
                Objects.equals(masterInstanceId, that.masterInstanceId) &&
                Objects.equals(masterIp, that.masterIp) &&
                Objects.equals(masterDns, that.masterDns) &&
                Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterId, error);
    }

    @Override
    public String toString() {
        return "CreateClusterResponse{" +
                "clusterId='" + clusterId + '\'' +
                ", masterInstanceId='" + masterInstanceId + '\'' +
                ", masterIp='" + masterIp + '\'' +
                ", masterDns='" + masterDns + '\'' +
                ", error='" + error + '\'' +
                '}';
    }
}
