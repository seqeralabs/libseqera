/*
 * Copyright 2025, Seqera Labs
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

package io.seqera.util.http

import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpParameters
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import spock.lang.Specification

/**
 * Unit tests for HttpUtil
 *
 * @author Paolo Di Tommaso
 */
class HttpUtilTest extends Specification {

    def 'should dump headers from map'() {
        given:
        def headers = [
            'Content-Type': ['application/json'],
            'Authorization': ['Bearer token123'],
            'Accept': ['application/json', 'text/html']
        ]

        when:
        def result = HttpUtil.dumpHeaders(headers)

        then:
        result.contains('Content-Type=application/json')
        result.contains('Authorization=Bearer token123')
        result.contains('Accept=application/json')
        result.contains('Accept=text/html')
    }

    def 'should return null for empty headers map'() {
        expect:
        HttpUtil.dumpHeaders([:]) == null
        HttpUtil.dumpHeaders(null as Map) == null
    }

    def 'should dump headers from Micronaut request'() {
        given:
        def request = Mock(HttpRequest) {
            getHeaders() >> Mock(HttpHeaders) {
                asMap() >> ['Content-Type': ['application/json']]
            }
        }

        when:
        def result = HttpUtil.dumpHeaders(request)

        then:
        result.contains('Content-Type=application/json')
    }

    def 'should dump headers from Micronaut response'() {
        given:
        def response = Mock(HttpResponse) {
            getHeaders() >> Mock(HttpHeaders) {
                asMap() >> ['Content-Type': ['text/html']]
            }
        }

        when:
        def result = HttpUtil.dumpHeaders(response)

        then:
        result.contains('Content-Type=text/html')
    }

    def 'should dump headers from Java HttpRequest'() {
        given:
        def request = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create('https://example.com'))
            .header('Content-Type', 'application/json')
            .header('Authorization', 'Bearer token123')
            .build()

        when:
        def result = HttpUtil.dumpHeaders(request)

        then:
        result.contains('Content-Type=application/json')
        result.contains('Authorization=Bearer token123')
    }

    def 'should dump headers from Java HttpResponse'() {
        given:
        def request = java.net.http.HttpRequest.newBuilder()
            .uri(URI.create('https://example.com'))
            .build()

        def mockClient = Mock(java.net.http.HttpClient)
        def response = Mock(java.net.http.HttpResponse) {
            headers() >> java.net.http.HttpHeaders.of(
                ['Content-Type': ['text/plain'], 'Server': ['nginx']],
                { k, v -> true }
            )
        }

        when:
        def result = HttpUtil.dumpHeaders(response)

        then:
        result.contains('Content-Type=text/plain')
        result.contains('Server=nginx')
    }

    def 'should handle multiple values for same header'() {
        given:
        def headers = [
            'Set-Cookie': ['session=abc123', 'tracking=xyz789', 'lang=en']
        ]

        when:
        def result = HttpUtil.dumpHeaders(headers)

        then:
        result.contains('Set-Cookie=session=abc123')
        result.contains('Set-Cookie=tracking=xyz789')
        result.contains('Set-Cookie=lang=en')
        result.split('\n').count { it.contains('Set-Cookie=') } == 3
    }

    def 'should dump params from map'() {
        given:
        def params = [
            'code': ['auth_code_123'],
            'state': ['secure_state_456'],
            'redirect_uri': ['http://localhost:8080/callback']
        ]

        when:
        def result = HttpUtil.dumpParams(params)

        then:
        result.contains('code=auth_code_123')
        result.contains('state=secure_state_456')
        result.contains('redirect_uri=http://localhost:8080/callback')
    }

    def 'should return null for empty params map'() {
        expect:
        HttpUtil.dumpParams([:]) == null
        HttpUtil.dumpParams(null as Map) == null
    }

    def 'should dump params from Micronaut request'() {
        given:
        def request = Mock(HttpRequest) {
            getParameters() >> Mock(HttpParameters) {
                asMap() >> ['code': ['auth_code_123']]
            }
        }

        when:
        def result = HttpUtil.dumpParams(request)

        then:
        result.contains('code=auth_code_123')
    }

    def 'should handle multiple values for same param'() {
        given:
        def params = [
            'tags': ['java', 'groovy', 'micronaut']
        ]

        when:
        def result = HttpUtil.dumpParams(params)

        then:
        result.contains('tags=java')
        result.contains('tags=groovy')
        result.contains('tags=micronaut')
        result.split('\n').count { it.contains('tags=') } == 3
    }

    def 'should mask sensitive params with default keys'() {
        given:
        def params = [
            'client_secret': 'very_secret_value_here',
            'code': 'authorization_code_123',
            'refresh_token': 'refresh_token_value',
            'access_token': 'access_token_value',
            'password': 'user_password',
            'username': 'john.doe'
        ]

        when:
        def result = HttpUtil.maskParams(params)

        then:
        result['client_secret'] == 'very_secre...'
        result['code'] == 'authorizat...'
        result['refresh_token'] == 'refresh_to...'
        result['access_token'] == 'access_tok...'
        result['password'] == 'user_passw...'
        result['username'] == 'john.doe'  // not masked
    }

    def 'should mask sensitive params with custom keys'() {
        given:
        def params = [
            'api_key': 'my_api_key_value',
            'username': 'john.doe'
        ]
        def sensitiveKeys = ['api_key']

        when:
        def result = HttpUtil.maskParams(params, sensitiveKeys)

        then:
        result['api_key'] == 'my_api_key...'
        result['username'] == 'john.doe'
    }

    def 'should handle null params in maskParams'() {
        expect:
        HttpUtil.maskParams(null) == null
    }

    def 'should handle short values in maskParams'() {
        given:
        def params = [
            'password': 'short'
        ]

        when:
        def result = HttpUtil.maskParams(params)

        then:
        result['password'] == 'short...'
    }

    def 'should handle null values in maskParams'() {
        given:
        def params = [
            'password': null,
            'username': 'john.doe'
        ]

        when:
        def result = HttpUtil.maskParams(params)

        then:
        result['password'] == '...'
        result['username'] == 'john.doe'
    }

    def 'should handle empty string values in maskParams'() {
        given:
        def params = [
            'password': '',
            'username': 'john.doe'
        ]

        when:
        def result = HttpUtil.maskParams(params)

        then:
        result['password'] == '...'
        result['username'] == 'john.doe'
    }

    def 'should preserve original map in maskParams'() {
        given:
        def params = [
            'password': 'secret_password',
            'username': 'john.doe'
        ]
        def originalPassword = params['password']

        when:
        def result = HttpUtil.maskParams(params)

        then:
        params['password'] == originalPassword  // original unchanged
        result['password'] == 'secret_pas...'    // result masked
    }

    def 'should mask multiple sensitive keys with custom list'() {
        given:
        def params = [
            'api_key': '1234567890abcdef',
            'secret_key': 'secret_key_value',
            'username': 'john.doe',
            'email': 'john@example.com'
        ]
        def sensitiveKeys = ['api_key', 'secret_key']

        when:
        def result = HttpUtil.maskParams(params, sensitiveKeys)

        then:
        result['api_key'] == '1234567890...'
        result['secret_key'] == 'secret_key...'
        result['username'] == 'john.doe'
        result['email'] == 'john@example.com'
    }

    def 'should handle headers with special characters'() {
        given:
        def headers = [
            'X-Custom-Header': ['value-with-dashes'],
            'Content-Type': ['application/json; charset=utf-8']
        ]

        when:
        def result = HttpUtil.dumpHeaders(headers)

        then:
        result.contains('X-Custom-Header=value-with-dashes')
        result.contains('Content-Type=application/json; charset=utf-8')
    }

    def 'should handle params with URL encoded values'() {
        given:
        def params = [
            'redirect_uri': ['http://localhost:8080/callback?state=xyz'],
            'scope': ['openid profile email']
        ]

        when:
        def result = HttpUtil.dumpParams(params)

        then:
        result.contains('redirect_uri=http://localhost:8080/callback?state=xyz')
        result.contains('scope=openid profile email')
    }

    def 'should format output with consistent newlines and indentation'() {
        given:
        def headers = [
            'Authorization': ['Bearer token'],
            'Content-Type': ['application/json']
        ]

        when:
        def result = HttpUtil.dumpHeaders(headers)

        then:
        result.startsWith('\n  ')
        result.split('\n').findAll { it.trim() }.every { it.startsWith('  ') }
    }

    def 'should handle integer and other object types in maskParams'() {
        given:
        def params = [
            'password': 'secret_password',
            'user_id': 12345,
            'is_active': true,
            'score': 98.5
        ]

        when:
        def result = HttpUtil.maskParams(params)

        then:
        result['password'] == 'secret_pas...'
        result['user_id'] == 12345
        result['is_active'] == true
        result['score'] == 98.5
    }

    def 'should mask object types that are sensitive'() {
        given:
        def params = [
            'password': 12345678901234,  // numeric password
            'username': 'john.doe'
        ]

        when:
        def result = HttpUtil.maskParams(params)

        then:
        result['password'] == '1234567890...'
        result['username'] == 'john.doe'
    }
}
