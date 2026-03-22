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
 */

package io.seqera.platform.auth.oidc

import spock.lang.Specification

class OidcTokenExchangeTest extends Specification {

    def 'should extract access token from response'() {
        given:
        def json = '''{
            "access_token": "eyJhbGciOiJSUzI1NiJ9.test.signature",
            "refresh_token": "rt_abc123",
            "expires_in": 3600,
            "token_type": "Bearer"
        }'''
        when:
        def token = OidcTokenExchange.extractAccessToken(json)
        then:
        token == 'eyJhbGciOiJSUzI1NiJ9.test.signature'
    }

    def 'should throw when access_token is missing'() {
        given:
        def json = '{"refresh_token": "rt_abc", "expires_in": 3600}'
        when:
        OidcTokenExchange.extractAccessToken(json)
        then:
        thrown(IllegalArgumentException)
    }
}
