/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.config;

import java.util.List;
import java.util.Map;

/**
 * Spack build options
 *
 * @author Marco De La Pierre <marco.delapierre@gmail.com>
 */
public class SpackOpts {

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
}
