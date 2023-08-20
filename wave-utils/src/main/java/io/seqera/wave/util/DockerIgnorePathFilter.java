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

package io.seqera.wave.util;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Utility class to filter paths according docker ignore rules
 *
 * See https://docs.docker.com/engine/reference/builder/#dockerignore-file
 *
 * @author Munish Chouhan munish.chouhan@seqera.io
 */
public class DockerIgnorePathFilter {

    private Set<String> patterns;

    private final String EXCEPTION_PATTERN_MARKER = "!";

    Map<String, PathMatcher> mactherMap = new HashMap<>();

    public DockerIgnorePathFilter(Set<String> patterns) {
        this.patterns = patterns;
        FileSystem fileSystem = FileSystems.getDefault();
        for (String pattern : patterns){
            if (pattern.startsWith(EXCEPTION_PATTERN_MARKER)){
                mactherMap.put(pattern,fileSystem.getPathMatcher("glob:" + pattern.substring(1)));
            }else{
                mactherMap.put(pattern,fileSystem.getPathMatcher("glob:" + pattern));
            }
        }
    }

    public boolean accept(Path path) {
        boolean accepted = true;

        for (String pattern : patterns) {
            if (mactherMap.get(pattern).matches(path)) {
                accepted = pattern.startsWith(EXCEPTION_PATTERN_MARKER);
            }
        }

        return accepted;
    }
}
