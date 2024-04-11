/*
 * Copyright 2024, Seqera Labs
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

package io.seqera.wave.api;


import java.util.*;
import java.util.stream.Collectors;

import static io.seqera.wave.api.ObjectUtils.isEmpty;

/**
 * Model a container configuration
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ContainerConfig {

    public  List<String> entrypoint;

    public List<String> cmd;

    public List<String> env;

    /**
     * Image labels
     */
    public Map<String, String> labels;

    public String workingDir;

    public List<ContainerLayer> layers;

    public ContainerConfig() {
        entrypoint = null;
        cmd = null;
        env = null;
        labels = new HashMap<>();
        workingDir = null;
        layers = new ArrayList<>();
    }

    public ContainerConfig(List<String> entrypoint, List<String> cmd, List<String> env, Map<String, String> labels, String workDir, List<ContainerLayer> layers) {
        this.entrypoint = entrypoint;
        this.cmd = cmd;
        this.env = env;
        this.labels = labels;
        this.workingDir = workDir;
        this.layers = layers;
    }

    /**
     * Implements Groovy truth
     * 
     * @return {@code true} when at lest one field is populated or {@code false} otherwise
     */
    public boolean asBoolean() {
        return !empty();
    }

    public boolean empty() {
        return isEmpty(entrypoint) &&
                isEmpty(cmd) &&
                isEmpty(env) &&
                isEmpty(labels) &&
                isEmpty(workingDir) &&
                isEmpty(layers);
    }

    public void validate(){
        for( ContainerLayer it : layers ) {
            it.validate();
        }
    }

    public boolean hasFusionLayer() {
        if( isEmpty(layers) )
            return false;
        for( ContainerLayer it : layers ) {
            if( !isEmpty(it.location) && it.location.contains("https://fusionfs.seqera.io") )
                return true;
        }
        return false;
    }

    public FusionVersion fusionVersion() {
        if( isEmpty(layers) )
            return null;
        for( ContainerLayer it : layers ) {
            if( !isEmpty(it.location) && it.location.startsWith("https://fusionfs.seqera.io/") )
                return FusionVersion.from(it.location);
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("ContainerConfig[entrypoint=%s; cmd=%s; env=%s; labels=%s; workingDir=%s; layers=%s]", entrypoint, cmd, env, labels, workingDir, layers);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContainerConfig that = (ContainerConfig) o;
        return Objects.equals(entrypoint, that.entrypoint) && Objects.equals(cmd, that.cmd) && Objects.equals(env, that.env)
                && Objects.equals(labels, that.labels) && Objects.equals(workingDir, that.workingDir) && Objects.equals(layers, that.layers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entrypoint, cmd, env, labels, workingDir, layers);
    }

    static ContainerConfig copy(ContainerConfig that) {
        return copy(that, false);
    }

    /**
     * Copy method
     *
     * @param that The {@link ContainerConfig} to be copied from
     */
    static public ContainerConfig copy(ContainerConfig that, boolean stripData) {
        if( that==null )
            return null;
        return new ContainerConfig(
                that.entrypoint!=null ? new ArrayList<>(that.entrypoint) : null,
                that.cmd!=null ? new ArrayList<>(that.cmd) : null,
                that.env!=null ? new ArrayList<>(that.env) : null,
                that.labels != null ? that.labels.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)) : null,
                that.workingDir,
                that.layers != null ? that.layers.stream().map(it -> ContainerLayer.copy(it,stripData)).collect(Collectors.toList()) : null
        );
    }

}
