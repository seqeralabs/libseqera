/*
 * Copyright 2026, Seqera Labs
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

package io.seqera.service.pairing

import java.util.Locale

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Reduce a paired service endpoint to one canonical spelling.
 *
 * The pairing store key, the channel routing target and the deny-host check all use this form,
 * so that spellings of the same address differing only in the case of the scheme or host, a
 * default port or a trailing slash resolve to one pairing, instead of letting each spelling be
 * paired, and answered, separately.
 *
 * <p><b>The output of {@link #normalise} is frozen.</b> It is part of persisted identities: the
 * pairing store key here, and the private build id Wave derives from the tenant endpoint. Any
 * change to it, however small, re-keys every existing pairing and shifts every such id. Treat a
 * change as a breaking one and coordinate it with Wave's {@code ContainerHelper.normalisePlatformEndpoint}.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class PairingEndpoint {

    /**
     * Lowercase the scheme and host, drop the default port and any trailing slash. The path keeps
     * its case. Lower-casing uses {@link Locale#ROOT}, so the result does not depend on the JVM
     * default locale (e.g. a Turkish one maps {@code I} to a dotless {@code ı}). Rebuilding the URI also drops any user info, query string and fragment. A value
     * that is not an absolute URL is only trimmed.
     *
     * @param endpoint the endpoint as declared by the client
     * @return the canonical endpoint, or {@code null} when the endpoint is null or empty
     */
    static String normalise(String endpoint) {
        if( !endpoint )
            return null

        final result = endpoint.trim()
        try {
            final uri = new URI(result)
            if( !uri.scheme || !uri.host )
                return result
            final scheme = uri.scheme.toLowerCase(Locale.ROOT)
            final port = uri.port == defaultPort0(scheme) ? -1 : uri.port
            final path = uri.path.replaceAll('/+$', '')
            return new URI(scheme, null, uri.host.toLowerCase(Locale.ROOT), port, path, null, null).toString()
        }
        catch( URISyntaxException e ) {
            log.debug "Unable to normalise pairing endpoint: '$endpoint' - cause: ${e.message}"
            return result
        }
    }

    static private int defaultPort0(String scheme) {
        return scheme == 'https' ? 443 : scheme == 'http' ? 80 : -1
    }
}
