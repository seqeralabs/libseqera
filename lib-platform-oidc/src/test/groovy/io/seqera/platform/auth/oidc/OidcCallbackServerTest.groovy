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
import java.util.concurrent.TimeUnit

import spock.lang.Specification

class OidcCallbackServerTest extends Specification {

    def 'should receive authorization code on valid callback'() {
        given:
        def state = 'test-state-123'
        def server = new OidcCallbackServer(state)
        def client = HttpClient.newHttpClient()

        when:
        // simulate browser redirect
        def uri = URI.create("${server.redirectUri}?code=AUTH_CODE_XYZ&state=${state}")
        client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        def code = server.waitForCode(5, TimeUnit.SECONDS)

        then:
        code == 'AUTH_CODE_XYZ'

        cleanup:
        server.close()
    }

    def 'should reject callback with wrong state'() {
        given:
        def server = new OidcCallbackServer('expected-state')
        def client = HttpClient.newHttpClient()

        when:
        def uri = URI.create("${server.redirectUri}?code=abc&state=wrong-state")
        client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        server.waitForCode(5, TimeUnit.SECONDS)

        then:
        def e = thrown(Exception)
        e.message.contains('state mismatch')

        cleanup:
        server.close()
    }

    def 'should handle error callback'() {
        given:
        def server = new OidcCallbackServer('some-state')
        def client = HttpClient.newHttpClient()

        when:
        def uri = URI.create("${server.redirectUri}?error=access_denied&error_description=User+denied")
        client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        server.waitForCode(5, TimeUnit.SECONDS)

        then:
        def e = thrown(Exception)
        e.message.contains('User denied')

        cleanup:
        server.close()
    }

    def 'should bind to ephemeral port'() {
        given:
        def server = new OidcCallbackServer('state')

        expect:
        server.port > 0
        server.redirectUri.startsWith('http://127.0.0.1:')
        server.redirectUri.endsWith('/callback')

        cleanup:
        server.close()
    }

    def 'should timeout when no callback received'() {
        given:
        def server = new OidcCallbackServer('state')

        when:
        server.waitForCode(50, TimeUnit.MILLISECONDS)

        then:
        def e = thrown(IOException)
        e.message.contains('timed out')

        cleanup:
        server.close()
    }

    def 'should reject callback with missing code'() {
        given:
        def server = new OidcCallbackServer('my-state')
        def client = HttpClient.newHttpClient()

        when:
        def uri = URI.create("${server.redirectUri}?state=my-state")
        client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        server.waitForCode(5, TimeUnit.SECONDS)

        then:
        def e = thrown(Exception)
        e.message.contains('missing authorization code')

        cleanup:
        server.close()
    }

    def 'should parse query parameters'() {
        expect:
        OidcCallbackServer.parseQuery(URI.create('http://x/cb?a=1&b=hello+world')) == [a: '1', b: 'hello world']
        OidcCallbackServer.parseQuery(URI.create('http://x/cb')) == [:]
    }
}
