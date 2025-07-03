package io.seqera.sched.api.v1a1;

import java.util.Objects;

public class RemoveNodeRequest {
    public String clusterId;
    public String nodeId;

    public RemoveNodeRequest withClusterId(String clusterId) {
        this.clusterId = clusterId;
        return this;
    }

    public RemoveNodeRequest withNodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        RemoveNodeRequest that = (RemoveNodeRequest) o;
        return Objects.equals(clusterId, that.clusterId) && Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public String toString() {
        return "RemoveNodeRequest{" +
                "clusterId='" + clusterId + '\'' +
                ", nodeId='" + nodeId + '\'' +
                '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterId, nodeId);
    }
}
