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

package io.seqera.http

import static com.github.tomakehurst.wiremock.client.WireMock.*

import java.net.http.HttpRequest
import java.net.http.HttpResponse

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.seqera.http.auth.AuthenticationCallback
import io.seqera.http.auth.AuthenticationScheme
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Integration test for HxClient WWW-Authenticate handling using WireMock.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientWwwAuthIntegrationTest extends Specification {

    @Shared
    WireMockServer wireMockServer

    def setupSpec() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort())
        wireMockServer.start()
        WireMock.configureFor("localhost", wireMockServer.port())
    }

    def cleanupSpec() {
        wireMockServer?.stop()
    }

    def cleanup() {
        wireMockServer.resetAll()
    }

    private String baseUrl() {
        return "http://localhost:${wireMockServer.port()}"
    }

    def "should automatically handle Bearer WWW-Authenticate challenge when enabled"() {
        given: 'endpoint returns 401 with Bearer challenge pointing to local token endpoint'
        wireMockServer.stubFor(get(urlEqualTo('/v2/repo/blobs/sha256:abc123'))
                .withHeader('Authorization', absent())
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader('WWW-Authenticate',
                                "Bearer realm=\"${baseUrl()}/token\",service=\"registry.example.com\",scope=\"repository:repo:pull\"")))

        and: 'token endpoint returns anonymous token'
        wireMockServer.stubFor(get(urlPathEqualTo('/token'))
                .withQueryParam('service', equalTo('registry.example.com'))
                .withQueryParam('scope', equalTo('repository:repo:pull'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Content-Type', 'application/json')
                        .withBody('{"token": "anon-token-123", "expires_in": 300}')))

        and: 'endpoint succeeds with Bearer token'
        wireMockServer.stubFor(get(urlEqualTo('/v2/repo/blobs/sha256:abc123'))
                .withHeader('Authorization', equalTo('Bearer anon-token-123'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('blob-content')))

        and: 'HxClient configured for WWW-Authenticate handling'
        def config = HxConfig.newBuilder()
                .wwwAuthentication(true)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        when:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl()}/v2/repo/blobs/sha256:abc123"))
                .GET()
                .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: 'should succeed after automatic authentication'
        response.statusCode() == 200
        response.body() == 'blob-content'

        and: 'original request was made, then token fetched, then retry with auth'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/v2/repo/blobs/sha256:abc123')))
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo('/token')))
    }

    def "should handle WWW-Authenticate challenge with callback providing Basic credentials"() {
        given: 'endpoint returns 401 with Basic challenge'
        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .withHeader('Authorization', absent())
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader('WWW-Authenticate', 'Basic realm="Protected Area"')))

        and: 'endpoint succeeds with valid Basic credentials'
        def encodedCreds = Base64.getEncoder().encodeToString("user:pass".getBytes())
        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .withHeader('Authorization', equalTo("Basic ${encodedCreds}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('secret-data')))

        and: 'callback provides credentials for Basic scheme'
        def callback = { scheme, realm ->
            if (scheme == AuthenticationScheme.BASIC && realm == "Protected Area") {
                return encodedCreds
            }
            return null
        } as AuthenticationCallback

        def config = HxConfig.newBuilder()
                .wwwAuthentication(true)
                .wwwAuthenticationCallback(callback)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        when:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl()}/api/data"))
                .GET()
                .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: 'should succeed with callback-provided credentials'
        response.statusCode() == 200
        response.body() == 'secret-data'
    }

    def "should not handle WWW-Authenticate when feature is disabled"() {
        given: 'endpoint returns 401 with WWW-Authenticate header'
        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader('WWW-Authenticate', 'Bearer realm="api"')))

        and: 'HxClient with WWW-Authenticate handling disabled'
        def config = HxConfig.newBuilder()
                .wwwAuthentication(false)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        when:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl()}/api/data"))
                .GET()
                .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: 'should return 401 without retry'
        response.statusCode() == 401

        and: 'only one request was made (no auth retry)'
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/data')))
    }

    def "should handle Bearer challenge with access_token field in response"() {
        given: 'endpoint returns 401 with Bearer challenge'
        wireMockServer.stubFor(get(urlEqualTo('/api/resource'))
                .withHeader('Authorization', absent())
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader('WWW-Authenticate',
                                "Bearer realm=\"${baseUrl()}/token\",service=\"api.example.com\",scope=\"read\"")))

        and: 'token endpoint returns access_token (alternative JSON field)'
        wireMockServer.stubFor(get(urlPathEqualTo('/token'))
                .withQueryParam('service', equalTo('api.example.com'))
                .withQueryParam('scope', equalTo('read'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Content-Type', 'application/json')
                        .withBody('{"access_token": "oauth-token-456", "token_type": "Bearer"}')))

        and: 'endpoint succeeds with the token'
        wireMockServer.stubFor(get(urlEqualTo('/api/resource'))
                .withHeader('Authorization', equalTo('Bearer oauth-token-456'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('ok')))

        def config = HxConfig.newBuilder()
                .wwwAuthentication(true)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        when:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl()}/api/resource"))
                .GET()
                .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'ok'
    }

    @Unroll
    def "should handle different callback results: #SCENARIO"() {
        given: 'endpoint returns 401 with Basic challenge'
        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader('WWW-Authenticate', 'Basic realm="Test"')))

        and: 'HxClient with callback'
        def callback = CALLBACK as AuthenticationCallback
        def config = HxConfig.newBuilder()
                .wwwAuthentication(true)
                .wwwAuthenticationCallback(callback)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        when:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl()}/api/data"))
                .GET()
                .build()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: 'should receive expected status'
        response.statusCode() == EXPECTED_STATUS

        where:
        SCENARIO           | CALLBACK                                                          | EXPECTED_STATUS
        "returns null"     | { scheme, realm -> null }                                         | 401  // falls back to anonymous Basic (empty creds), server rejects
        "throws exception" | { scheme, realm -> throw new RuntimeException("Callback error") } | 401  // falls back to anonymous Basic, server rejects
        "returns empty"    | { scheme, realm -> "" }                                           | 401  // empty credentials, server rejects
    }
}
