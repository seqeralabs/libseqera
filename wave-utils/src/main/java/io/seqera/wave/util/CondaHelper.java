package io.seqera.wave.util;

import java.util.List;
import java.util.Optional;
/**
 * Helper class to create conda artifacts
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
public class CondaHelper {
    public static String condaLock(List<String> packages) {
        if( packages==null || packages.isEmpty() )
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
}
