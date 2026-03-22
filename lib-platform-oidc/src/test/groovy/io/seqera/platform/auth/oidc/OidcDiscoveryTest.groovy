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

class OidcDiscoveryTest extends Specification {

    def 'should parse openid-configuration response'() {
        given:
        def json = '''{
            "issuer": "https://platform.example.com",
            "authorization_endpoint": "https://platform.example.com/authorize",
            "token_endpoint": "https://platform.example.com/token",
            "jwks_uri": "https://platform.example.com/.well-known/jwks.json"
        }'''
        when:
        def config = OidcDiscovery.parseConfig(json)
        then:
        config.authorizationEndpoint == 'https://platform.example.com/authorize'
        config.tokenEndpoint == 'https://platform.example.com/token'
    }

    def 'should throw when authorization_endpoint is missing'() {
        given:
        def json = '{"token_endpoint": "https://platform.example.com/token"}'
        when:
        OidcDiscovery.parseConfig(json)
        then:
        thrown(IllegalArgumentException)
    }

    def 'should throw when token_endpoint is missing'() {
        given:
        def json = '{"authorization_endpoint": "https://platform.example.com/authorize"}'
        when:
        OidcDiscovery.parseConfig(json)
        then:
        thrown(IllegalArgumentException)
    }

    def 'should extract json string value'() {
        expect:
        OidcDiscovery.extractJsonString('{"foo": "bar"}', 'foo') == 'bar'
        OidcDiscovery.extractJsonString('{"foo": "bar"}', 'baz') == null
        OidcDiscovery.extractJsonString('{"a": "1", "b": "2"}', 'b') == '2'
    }
}
