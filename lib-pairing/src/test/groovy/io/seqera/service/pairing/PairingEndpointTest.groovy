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

import io.seqera.service.pairing.socket.PairingChannel
import spock.lang.Specification

/**
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class PairingEndpointTest extends Specification {

    def 'should normalise pairing endpoints' () {
        expect:
        PairingEndpoint.normalise(ENDPOINT) == EXPECTED

        where:
        ENDPOINT                                | EXPECTED
        null                                    | null
        ''                                      | null
        'https://api.cloud.seqera.io'           | 'https://api.cloud.seqera.io'
        // trailing slash dropped
        'https://api.cloud.seqera.io/'          | 'https://api.cloud.seqera.io'
        'https://foo.com/api/'                  | 'https://foo.com/api'
        // scheme and host lowercased, path left alone
        'HTTPS://API.CLOUD.SEQERA.IO'           | 'https://api.cloud.seqera.io'
        'https://FOO.com/Api'                   | 'https://foo.com/Api'
        // default port dropped, explicit one kept
        'https://foo.com:443'                   | 'https://foo.com'
        'http://foo.com:80/api'                 | 'http://foo.com/api'
        'https://foo.com:8443'                  | 'https://foo.com:8443'
        // not a parseable absolute URL: trimmed and left as-is
        'foo.com'                               | 'foo.com'
        '  https://foo.com  '                   | 'https://foo.com'
        // rebuilding the URI drops user info, query and fragment
        'https://user:pass@foo.com/api'         | 'https://foo.com/api'
        'https://foo.com/api?x=1'               | 'https://foo.com/api'
        'https://foo.com/api#frag'              | 'https://foo.com/api'
    }

    def 'should give every spelling of one address the same pairing key' () {
        given:
        def key = PairingServiceImpl.makeKey('tower', 'https://foo.com/api')

        expect:
        PairingServiceImpl.makeKey('tower', 'https://foo.com/api/') == key
        PairingServiceImpl.makeKey('tower', 'HTTPS://FOO.com/api') == key
        PairingServiceImpl.makeKey('tower', 'https://foo.com:443/api') == key

        and: 'a different path or service is a different pairing'
        PairingServiceImpl.makeKey('tower', 'https://foo.com/API') != key
        PairingServiceImpl.makeKey('other', 'https://foo.com/api') != key
    }

    def 'should keep the key of an endpoint already in canonical form' () {
        expect: 'records paired under a canonical spelling are found after the upgrade'
        PairingServiceImpl.makeKey('tower', 'https://foo.com/api') == CacheKey.md5([service: 'tower', towerEndpoint: 'https://foo.com/api'] as Map<String,Object>)
    }

    def 'should route every spelling of one address to the same target' () {
        expect:
        PairingChannel.clientTarget('tower', 'https://foo.com/api') == 'tower:foo.com/api'
        PairingChannel.clientTarget('tower', 'HTTPS://FOO.com:443/api/') == 'tower:foo.com/api'
    }
}
