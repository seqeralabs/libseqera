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

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Utility class to filter paths according docker ignore rules
 *
 * See https://docs.docker.com/engine/reference/builder/#dockerignore-file
 *
 * @author Munish Chouhan munish.chouhan@seqera.io
 */
public class DockerIgnoreFilter implements Predicate<Path> {

    final private List<String> ignoreGlobs;

    final private Map<String, PathMatcher> mactherMap;

    /**
     * Creates a filter with the specified list of glob ingore glob patterns
     *
     * @param ignoreGlobs A list of ignore glob patterns
     */
    DockerIgnoreFilter(List<String> ignoreGlobs) {
        this.ignoreGlobs = ignoreGlobs;
        this.mactherMap = new HashMap<>();
        final FileSystem fileSystem = FileSystems.getDefault();
        for (String it : ignoreGlobs){
            final String glob = it.startsWith("!") ? it.substring(1) : it;
            mactherMap.put(it, fileSystem.getPathMatcher("glob:" + glob));
        }
    }

    public List<String> ignoreGlobs() { return new ArrayList<>(ignoreGlobs); }

    /**
     * Test if the given path should be included or ignored according the pattern provided.
     *
     * @param path
     *      The {@link Path} to be tested
     * @return {@code true}
     *      when then path is valid i.e. should not be ignored; {@code false}
     *      when the path matches one or more ignore globs and therefore it should not be acepted
     */
    @Override
    public boolean test(Path path) {
        boolean accepted = true;

        for (String glob : ignoreGlobs) {
            if (mactherMap.get(glob).matches(path)) {
                accepted = glob.startsWith("!");
            }
        }

        return accepted;
    }

    /**
     * Creates a {@link DockerIgnoreFilter} instance from the specified docker ignore file path
     *
     * @param dockerIgnoreFile A path to a file formatted according the {@code .dockerignore} syntax
     * @return An instance of {@link DockerIgnoreFilter} holding the ignore rule specified by the file
     * @throws IOException Thrown if the file cannot be read
     */
    static public DockerIgnoreFilter fromFile(Path dockerIgnoreFile) throws IOException {
        List<String> dockerIgnorePatterns = Files.readAllLines(dockerIgnoreFile)
                .stream()
                .filter(it -> !it.trim().isEmpty() && !it.trim().startsWith("#"))
                .map(String::trim)
                .collect(Collectors.toList());

        return new DockerIgnoreFilter(dockerIgnorePatterns);
    }

    /**
     * Creates a {@link DockerIgnoreFilter} instance with the specified list of ignore globs
     * @param globs A list of globs for the files to be ignored
     * @return An instance of {@link DockerIgnoreFilter} holding the ignore rules specifie
     */
    static public DockerIgnoreFilter from(List<String> globs) {
        final List<String> clean = globs.stream().map(String::trim).collect(Collectors.toList());
        return new DockerIgnoreFilter(clean);
    }
}
