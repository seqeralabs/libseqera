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

import java.util.List;
import java.util.Objects;

import io.seqera.wave.config.CondaOpts;
import io.seqera.wave.config.SpackOpts;

/**
 * Model a Package environment requirements
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class PackagesSpec {

    public enum Type { CONDA, SPACK }

    public Type type;

    /**
     * The package environment file encoded as a base64 string. When this is provided the field {@link #packages} is not allowed
     */
    public String envFile;

    /**
     * A list of one or more packages. When this is provided the field {@link #envFile} is not allowed
     */
    public List<String> packages;

    /**
     * Conda build options
     */
    public CondaOpts condaOpts;

    /**
     * Spack build options
     */
    public SpackOpts spackOpts;

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        PackagesSpec that = (PackagesSpec) object;
        return type == that.type && Objects.equals(envFile, that.envFile) && Objects.equals(packages, that.packages) && Objects.equals(condaOpts, that.condaOpts) && Objects.equals(spackOpts, that.spackOpts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, envFile, packages, condaOpts, spackOpts);
    }

    @Override
    public String toString() {
        return "PackagesSpec{" +
                "type=" + type +
                ", envFile='" + envFile + '\'' +
                ", packages=" + packages +
                ", condaOpts=" + condaOpts +
                ", spackOpts=" + spackOpts +
                '}';
    }
}
