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
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class PairingEndpoint {

    /**
     * Lowercase the scheme and host, drop the default port and any trailing slash. The path keeps
     * its case. Rebuilding the URI also drops any user info, query string and fragment. A value
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
            final scheme = uri.scheme.toLowerCase()
            final port = uri.port == defaultPort0(scheme) ? -1 : uri.port
            final path = uri.path.replaceAll('/+$', '')
            return new URI(scheme, null, uri.host.toLowerCase(), port, path, null, null).toString()
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
