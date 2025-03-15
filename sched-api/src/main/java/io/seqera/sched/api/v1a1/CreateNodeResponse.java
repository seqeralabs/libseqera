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

public class CreateNodeResponse {

    public String clusterId;
    public String instanceId;
    public String ip;
    public String dns;
    public String error;

    public CreateNodeResponse withClusterId(String clusterId) {
        this.clusterId = clusterId;
        return this;
    }

    public CreateNodeResponse withMasterInstanceId(String masterInstanceId) {
        this.instanceId = masterInstanceId;
        return this;
    }

    public CreateNodeResponse withMasterIp(String masterIp) {
        this.ip = masterIp;
        return this;
    }

    public CreateNodeResponse withMasterDns(String masterDns) {
        this.dns = masterDns;
        return this;
    }

    public CreateNodeResponse withError(String error) {
        this.error = error;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CreateNodeResponse that = (CreateNodeResponse) o;
        return Objects.equals(clusterId, that.clusterId) &&
                Objects.equals(instanceId, that.instanceId) &&
                Objects.equals(ip, that.ip) &&
                Objects.equals(dns, that.dns) &&
                Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterId, error);
    }

    @Override
    public String toString() {
        return "CreateNodeResponse{" +
                "clusterId='" + clusterId + '\'' +
                ", instanceId='" + instanceId + '\'' +
                ", ip='" + ip + '\'' +
                ", dns='" + dns + '\'' +
                ", error='" + error + '\'' +
                '}';
    }
}
