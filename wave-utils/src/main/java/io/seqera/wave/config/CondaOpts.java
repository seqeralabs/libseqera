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
 * Conda build options
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CondaOpts {
    final public static String DEFAULT_MAMBA_IMAGE = "mambaorg/micromamba:1.5.1";
    final public static String DEFAULT_PACKAGES = "conda-forge::procps-ng";

    public String mambaImage;
    public List<String> commands;
    public String basePackages;

    public CondaOpts() {
        this(Map.of());
    }
    public CondaOpts(Map<String,?> opts) {
        this.mambaImage = opts.containsKey("mambaImage") ? opts.get("mambaImage").toString(): DEFAULT_MAMBA_IMAGE;
        this.commands = opts.containsKey("commands") ? (List<String>)opts.get("commands") : null;
        this.basePackages = opts.containsKey("basePackages") ? (String)opts.get("basePackages") : DEFAULT_PACKAGES;
    }

    public CondaOpts withMambaImage(String value) {
        this.mambaImage = value;
        return this;
    }

    public CondaOpts withCommands(List<String> value) {
        this.commands = value;
        return this;
    }

    public CondaOpts withBasePackages(String value) {
        this.basePackages = value;
        return this;
    }

    @Override
    public String toString() {
        return String.format("CondaOpts(mambaImage=%s; basePackages=%s, commands=%s)",
                mambaImage,
                basePackages,
                commands != null ? String.join(",", commands) : "null"
                );
    }
}
