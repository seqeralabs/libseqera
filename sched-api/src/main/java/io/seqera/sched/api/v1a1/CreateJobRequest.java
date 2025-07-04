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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Model a dead simple job request
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CreateJobRequest {

    public String contextId;
    public String clusterId;
    public List<String> command;
    public String image;
    public Map<String,String> environment;
    public String platform;

    public int cpus;
    public long memory;

    public CreateJobRequest withContextId(String contextId) {
        this.contextId = contextId;
        return this;
    }

    public CreateJobRequest withClusterId(String clusterId) {
        this.clusterId = clusterId;
        return this;
    }

    public CreateJobRequest withCommand(List<String> command) {
        this.command = command;
        return this;
    }

    public CreateJobRequest withImage(String image) {
        this.image = image;
        return this;
    }

    public CreateJobRequest withEnvironment(Map<String,String> environment) {
        this.environment = environment;
        return this;
    }

    public CreateJobRequest withPlatform(String platform) {
        this.platform = platform;
        return this;
    }

    public CreateJobRequest withCpus(int cpus) {
        this.cpus = cpus;
        return this;
    }

    public CreateJobRequest withMemory(long memory) {
        this.memory = memory;
        return this;
    }

    @Override
    public String toString() {
        return "CreateJobRequest{" +
                "image=" + image +
                ", command='" + command + "'" +
                ", environment='" + environment + "'" +
                ", platform='" + platform + "'" +
                ", clusterId='" + clusterId + "'" +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CreateJobRequest that = (CreateJobRequest) o;
        return Objects.equals(contextId, that.contextId)
                && Objects.equals(clusterId, that.clusterId)
                && Objects.equals(image, that.image)
                && Objects.equals(command, that.command)
                && Objects.equals(environment, that.environment)
                && Objects.equals(platform, that.platform)
                && Objects.equals(cpus, that.cpus)
                && Objects.equals(memory, that.memory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contextId, clusterId, image, command, environment, platform, cpus, memory);
    }
}
