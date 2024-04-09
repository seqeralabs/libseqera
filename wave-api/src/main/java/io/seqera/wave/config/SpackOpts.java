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

package io.seqera.wave.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Spack build options
 *
 * @author Marco De La Pierre <marco.delapierre@gmail.com>
 */
public class SpackOpts {

    public static final SpackOpts EMPTY = new SpackOpts();

    /**
     * Custom Dockerfile `RUN` commands that can be used to customise the target container
     */
    public List<String> commands;

    /**
     * Spack packages that should be added to any Spack environment requested via Wave
     */
    public String basePackages;

    public SpackOpts() {
        this(Map.of());
    }
    public SpackOpts(Map<String,?> opts) {
        this.commands = opts.containsKey("commands") ? (List<String>)opts.get("commands") : null;
        this.basePackages = opts.containsKey("basePackages") ? opts.get("basePackages").toString() : null;
    }

    public SpackOpts withCommands(List<String> value) {
        this.commands = value;
        return this;
    }

    public SpackOpts withBasePackages(String value) {
        this.basePackages = value;
        return this;
    }

    @Override
    public String toString() {
        return String.format("SpackOpts(basePackages=%s, commands=%s)",
                String.valueOf(basePackages),
                commands != null ? String.join(",", commands) : "null"
        );
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        SpackOpts spackOpts = (SpackOpts) object;
        return Objects.equals(commands, spackOpts.commands) && Objects.equals(basePackages, spackOpts.basePackages);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commands, basePackages);
    }
}
