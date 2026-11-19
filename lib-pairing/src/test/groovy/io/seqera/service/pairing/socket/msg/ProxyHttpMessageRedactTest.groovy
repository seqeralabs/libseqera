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

import spock.lang.Specification

/**
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
class ProxyHttpMessageRedactTest extends Specification {

    def 'should not expose the bearer, the body or the credential headers of a request' () {
        given:
        def req = new ProxyHttpRequest(
                msgId: 'msg-123',
                method: 'POST',
                uri: 'http://foo.com/oauth/access_token',
                auth: 'Bearer eyJhbGciOiJIUzI1NiJ9.the-access-token',
                body: 'grant_type=refresh_token&refresh_token=the-refresh-token',
                headers: ['Authorization': ['Bearer the-access-token'], 'Content-Type': ['application/json']] )

        when:
        def str = req.toString()

        then:
        !str.contains('the-access-token')
        !str.contains('the-refresh-token')
        and: 'the non-credential fields stay readable'
        str.contains('msg-123')
        str.contains('POST')
        str.contains('application/json')
    }

    def 'should not expose the body or the set-cookie of a response' () {
        given:
        def resp = new ProxyHttpResponse(
                msgId: 'msg-123',
                status: 200,
                body: '{"access_token":"the-access-token","refresh_token":"the-refresh-token"}',
                headers: ['set-cookie': ['JWT=the-access-token', 'JWT_REFRESH_TOKEN=the-refresh-token'],
                          'Content-Type': ['application/json']] )

        when:
        def str = resp.toString()

        then:
        !str.contains('the-access-token')
        !str.contains('the-refresh-token')
        and:
        str.contains('msg-123')
        str.contains('200')
        str.contains('application/json')
    }

    def 'should match credential header names regardless of case' () {
        expect:
        MessageRedact.isSensitiveHeader(HEADER) == EXPECTED

        where:
        HEADER                | EXPECTED
        'authorization'       | true
        'AUTHORIZATION'       | true
        'Set-Cookie'          | true
        'proxy-authorization' | true
        'X-Registry-Auth'     | true
        'content-type'        | false
        'www-authenticate'    | false
        null                  | false
    }

}
