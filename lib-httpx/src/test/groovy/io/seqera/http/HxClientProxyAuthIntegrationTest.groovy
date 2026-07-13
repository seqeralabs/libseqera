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

package io.seqera.http

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import spock.lang.Specification

/**
 * Integration tests for authenticated forward-proxy support in {@link HxClient},
 * using a local proxy that returns 407 without a {@code Proxy-Authorization} header
 * and 200 with the correct Basic credentials.
 *
 * <p>Note: the HTTPS tunnelling cases rely on {@code jdk.http.auth.tunneling.disabledSchemes}
 * being cleared for the test JVM (see this module's build.gradle), since the JDK strips
 * the Basic scheme from HTTPS CONNECT tunnelling by default.
 */
class HxClientProxyAuthIntegrationTest extends Specification {

    MockAuthProxyServer proxy
    int proxyPort

    def setup() {
        proxy = new MockAuthProxyServer('alice', 's3cret')
        proxyPort = proxy.start()
    }

    def cleanup() {
        proxy?.close()
    }

    private static Authenticator basicProxyAuth(String username, String password) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (requestorType != Authenticator.RequestorType.PROXY)
                    return null
                return new PasswordAuthentication(username, password.toCharArray())
            }
        }
    }

    private ProxySelector proxySelector() {
        return ProxySelector.of(new InetSocketAddress('127.0.0.1', proxyPort))
    }

    def 'should succeed via authenticating proxy when credentials are supplied'() {
        given:
        def client = HxClient.newBuilder()
                .proxy(proxySelector())
                .authenticator(basicProxyAuth('alice', 's3cret'))
                .build()
        def request = HttpRequest.newBuilder()
                .uri(URI.create('http://test-target.example.com/hello'))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'OK via proxy'

        and: 'the first attempt was challenged, the retry carried the Basic credentials'
        proxy.authHeaders.first() == 'NONE'
        proxy.authHeaders.last() == proxy.expectedAuthHeader()
    }

    def 'should get 407 from authenticating proxy when credentials are absent'() {
        given:
        def client = HxClient.newBuilder()
                .proxy(proxySelector())
                .build()
        def request = HttpRequest.newBuilder()
                .uri(URI.create('http://test-target.example.com/hello'))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 407
        proxy.authHeaders.every { it == 'NONE' }
    }

    def 'should authenticate the CONNECT tunnel for https targets'() {
        given: 'a client with proxy credentials and no retries (the mock proxy cannot complete a TLS tunnel)'
        def client = HxClient.newBuilder()
                .proxy(proxySelector())
                .authenticator(basicProxyAuth('alice', 's3cret'))
                .maxAttempts(1)
                .build()
        def request = HttpRequest.newBuilder()
                .uri(URI.create('https://test-target.example.com/hello'))
                .GET()
                .build()

        when:
        client.send(request, HttpResponse.BodyHandlers.ofString())

        then: 'the request fails during the TLS handshake, after the tunnel was authenticated'
        thrown(Exception)
        proxy.requestLines.size() >= 2
        proxy.requestLines.every { it.startsWith('CONNECT test-target.example.com:443') }
        proxy.authHeaders.first() == 'NONE'
        proxy.authHeaders.contains(proxy.expectedAuthHeader())
    }

    def 'should fail the CONNECT tunnel when credentials are absent'() {
        given:
        def client = HxClient.newBuilder()
                .proxy(proxySelector())
                .maxAttempts(1)
                .build()
        def request = HttpRequest.newBuilder()
                .uri(URI.create('https://test-target.example.com/hello'))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: 'the proxy challenge is never answered'
        response.statusCode() == 407
        proxy.authHeaders.every { it == 'NONE' }
    }

    def 'should route token refresh requests through the authenticating proxy'() {
        given: 'the proxy answers authorized requests with a token refresh JSON payload'
        proxy.responseBody = '{"access_token":"new-header.new-payload.new-signature","refresh_token":"refresh-2"}'
        proxy.responseContentType = 'application/json'
        and:
        def client = HxClient.newBuilder()
                .proxy(proxySelector())
                .authenticator(basicProxyAuth('alice', 's3cret'))
                .bearerToken('old-header.old-payload.old-signature')
                .refreshToken('refresh-1')
                .refreshTokenUrl('http://token.example.com/oauth/token')
                .build()

        when:
        def refreshed = client.tokenManager.getOrRefreshTokenAsync(client.tokenManager.getDefaultAuth()).get()

        then: 'the refresh succeeded through the proxy'
        refreshed != null
        refreshed.accessToken() == 'new-header.new-payload.new-signature'
        refreshed.refreshToken() == 'refresh-2'

        and: 'the token refresh POST carried the proxy credentials'
        proxy.requestLines.any { it.startsWith('POST http://token.example.com/oauth/token') }
        proxy.authHeaders.last() == proxy.expectedAuthHeader()
    }

    def 'should keep an explicitly supplied HttpClient verbatim'() {
        given:
        def existing = HttpClient.newHttpClient()

        when:
        def client = HxClient.newBuilder()
                .httpClient(existing)
                .build()

        then:
        client.getHttpClient().is(existing)
    }

    def 'should expose the proxy settings via the client config'() {
        given:
        def selector = proxySelector()
        def authenticator = basicProxyAuth('alice', 's3cret')

        when:
        def client = HxClient.newBuilder()
                .proxy(selector)
                .authenticator(authenticator)
                .build()

        then: 'the internal token refresh clients can inherit them'
        client.getConfig().getProxySelector().is(selector)
        client.getConfig().getProxyAuthenticator().is(authenticator)
    }

    def 'should route through the proxy via withProxyConfig and the HxProxyConfig builder'() {
        given: 'a proxy config assembled from explicit values'
        def proxyConfig = HxProxyConfig.newBuilder()
                .httpProxy('127.0.0.1', proxyPort, 'alice', 's3cret')
                .build()
        def client = HxClient.newBuilder()
                .withProxyConfig(proxyConfig)
                .build()
        def request = HttpRequest.newBuilder()
                .uri(URI.create('http://test-target.example.com/hello'))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'OK via proxy'
        proxy.authHeaders.last() == proxy.expectedAuthHeader()
    }

    def 'withProxyConfig should be a no-op for a null config'() {
        when:
        def client = HxClient.newBuilder().withProxyConfig(null).build()

        then:
        client.getConfig().getProxySelector() == null
        client.getConfig().getProxyAuthenticator() == null
    }
}
