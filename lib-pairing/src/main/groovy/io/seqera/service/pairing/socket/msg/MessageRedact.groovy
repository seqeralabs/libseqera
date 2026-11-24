/*
 * Copyright 2024-2025, Seqera Labs
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
 */

package io.seqera.service.pairing.socket.msg

import groovy.transform.CompileStatic
import io.seqera.util.string.StringUtils

/**
 * Masks the credentials carried by a proxied HTTP message so they cannot reach the logs.
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
@CompileStatic
class MessageRedact {

    // Set-Cookie is here because a Platform token refresh returns the minted JWT and refresh token in it
    private static final List<String> SENSITIVE_HEADERS = List.of('authorization', 'proxy-authorization', 'cookie', 'set-cookie', 'x-registry-auth')

    /** Header names are case-insensitive, so match without regard to case or locale */
    static boolean isSensitiveHeader(String name) {
        for( String it : SENSITIVE_HEADERS )
            if( it.equalsIgnoreCase(name) )
                return true
        return false
    }

    static Map<String,List<String>> headers(Map<String,List<String>> headers) {
        if( headers==null )
            return null
        final result = new LinkedHashMap<String,List<String>>(headers.size())
        for( Map.Entry<String,List<String>> entry : headers ) {
            result.put(entry.key, isSensitiveHeader(entry.key)
                    ? entry.value?.collect { StringUtils.redact(it) }
                    : entry.value)
        }
        return result
    }

    /** Keep the path readable but mask the query string: a token can be carried as a query param */
    static String uri(String uri) {
        if( uri==null )
            return null
        final int p = uri.indexOf('?')
        return p==-1 ? uri : uri.substring(0,p) + '?' + StringUtils.redact(uri.substring(p+1))
    }

}
