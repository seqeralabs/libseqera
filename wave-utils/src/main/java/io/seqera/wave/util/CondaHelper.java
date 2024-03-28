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

package io.seqera.wave.util;

import io.seqera.wave.api.PackagesSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.seqera.wave.util.DockerHelper.condaFileFromPackages;
import static io.seqera.wave.util.Checkers.isEmpty;

/**
 * Helper class to create conda artifacts
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
public class CondaHelper {
    public static String condaLock(List<String> packages) {
        if( isEmpty(packages) )
            return null;
        Optional<String> result = packages
                .stream()
                .filter(it->it.startsWith("http://") || it.startsWith("https://"))
                .findFirst();
        if( result.isEmpty() )
            return null;
        if( packages.size()!=1 ) {
            throw new IllegalArgumentException("No more than one Conda lock remote file can be specified at the same time");
        }
        return result.get();
    }

    public static String createCondaFileFromPackages(PackagesSpec packagesSpec) throws IOException {
        if( packagesSpec == null || packagesSpec.entries == null )
            return null;
        final String packages = String.join(" ", packagesSpec.entries);
        Path condaFilePath =  condaFileFromPackages(packages, processCondaChannels(packagesSpec.channels));
        if( condaFilePath!=null ){
            return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(condaFilePath.toString())));
        }
        return null;
    }

    public static List<String> processCondaChannels(List<String> condaChannels) {
        if( condaChannels==null )
            return null;
        // parse channels
        return condaChannels.stream()
                .map(String::trim)
                .filter(it -> !isEmpty(it))
                .collect(Collectors.toList());
    }

}
