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

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import spock.lang.Specification

class OidcLoginFlowTest extends Specification {

    def 'should complete full login flow'() {
        given:
        def oidcConfig = new OidcConfig(
            'https://platform.test/authorize',
            'https://platform.test/token'
        )
        def discovery = Mock(OidcDiscovery) {
            discover('https://platform.test/api') >> oidcConfig
        }
        def tokenExchange = Mock(OidcTokenExchange)
        def flow = OidcLoginFlow.builder()
            .endpoint('https://platform.test/api')
            .clientId('nextflow_cli')
            .discovery(discovery)
            .tokenExchange(tokenExchange)
            .build()

        and:
        String capturedAuthUrl = null

        when:
        def token = flow.login({ url ->
            capturedAuthUrl = url
            // Simulate browser redirect to the callback server
            // Extract redirect_uri and state from the auth URL
            def params = parseUrlParams(url)
            def redirectUri = params['redirect_uri']
            def state = params['state']
            // Hit the callback server with the code
            def callbackUrl = "${redirectUri}?code=test-auth-code&state=${state}"
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(callbackUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
        })

        then:
        // Verify the auth URL was constructed correctly
        capturedAuthUrl.startsWith('https://platform.test/authorize?')
        capturedAuthUrl.contains('client_id=nextflow_cli')
        capturedAuthUrl.contains('response_type=code')
        capturedAuthUrl.contains('code_challenge_method=S256')
        capturedAuthUrl.contains('redirect_uri=')

        and:
        // Verify token exchange was called with the correct code
        1 * tokenExchange.exchange('https://platform.test/token', 'nextflow_cli', 'test-auth-code', _, _) >> 'access-token-result'
        token == 'access-token-result'
    }

    private static Map<String, String> parseUrlParams(String url) {
        def query = url.substring(url.indexOf('?') + 1)
        def params = [:]
        query.split('&').each { pair ->
            def parts = pair.split('=', 2)
            params[parts[0]] = URLDecoder.decode(parts[1], 'UTF-8')
        }
        return params
    }
}
