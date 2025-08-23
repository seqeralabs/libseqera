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

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Shared
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * Integration tests for HxClient combining retry and JWT refresh functionality using WireMock
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientCombinedIntegrationTest extends Specification {

    @Shared
    WireMockServer wireMockServer

    // Sample JWT tokens
    static final String INITIAL_JWT = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJpbml0aWFsIiwibmFtZSI6IkluaXRpYWwgVG9rZW4iLCJpYXQiOjE1MTYyMzkwMjJ9.signature1'
    static final String REFRESHED_JWT = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJyZWZyZXNoZWQiLCJuYW1lIjoiUmVmcmVzaGVkIFRva2VuIiwiaWF0IjoxNTE2MjM5MDIyfQ.signature2'

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

    def 'should handle JWT refresh followed by server error retry'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken(INITIAL_JWT)
                .withRefreshToken('combined_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'Complex scenario: 401 -> refresh -> 500 -> retry -> success'
        wireMockServer.stubFor(get(urlEqualTo('/api/complex'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('complex-flow')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized'))
                .willSetStateTo('token-refreshed'))

        wireMockServer.stubFor(get(urlEqualTo('/api/complex'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .inScenario('complex-flow')
                .whenScenarioStateIs('token-refreshed')
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody('Server Error'))
                .willSetStateTo('server-error-retry'))

        wireMockServer.stubFor(get(urlEqualTo('/api/complex'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .inScenario('complex-flow')
                .whenScenarioStateIs('server-error-retry')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Finally Success')))

        and: 'token refresh endpoint'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Set-Cookie', "JWT=${REFRESHED_JWT}; Path=/; HttpOnly")
                        .withHeader('Set-Cookie', "JWT_REFRESH_TOKEN=new_refresh; Path=/; HttpOnly")))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/complex"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Finally Success'

        and: 'verify token refresh was called once'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))

        and: 'verify all API calls: 401 + refreshed 500 + refreshed 200'
        wireMockServer.verify(3, getRequestedFor(urlEqualTo('/api/complex')))
    }

    def 'should handle rate limiting with JWT token'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken(INITIAL_JWT)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(100))
                .withRetryStatusCodes([429, 500, 502, 503, 504] as Set)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'API returns 429 with rate limiting, then success'
        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('rate-limit-jwt')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader('Retry-After', '1')
                        .withBody('Rate Limited'))
                .willSetStateTo('rate-limit-ok'))

        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('rate-limit-jwt')
                .whenScenarioStateIs('rate-limit-ok')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Rate Limit Passed')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/rate-limited"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Rate Limit Passed'

        and: 'no token refresh needed'
        wireMockServer.verify(0, postRequestedFor(urlMatching('/oauth/.*')))
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/rate-limited')))
    }

    def 'should handle multiple 401s without duplicate token refresh'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken(INITIAL_JWT)
                .withRefreshToken('no_duplicate_refresh')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .withMaxAttempts(2)
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'API always returns 401 (token refresh fails to fix the issue)'
        wireMockServer.stubFor(get(urlEqualTo('/api/persistent-401'))
                .withHeader('Authorization', matching("Bearer .*"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Still Unauthorized')))

        and: 'token refresh endpoint returns valid response'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Set-Cookie', "JWT=${REFRESHED_JWT}; Path=/; HttpOnly")))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/persistent-401"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 401
        response.body() == 'Still Unauthorized'

        and: 'token refresh should only be attempted once per request cycle'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))

        and: 'API should be called twice: original + after refresh'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/persistent-401')))
    }

    def 'should handle network errors with JWT authentication'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken(INITIAL_JWT)
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'API returns connection error, then success'
        wireMockServer.stubFor(get(urlEqualTo('/api/network-error'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('network-error-jwt')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo('network-ok'))

        wireMockServer.stubFor(get(urlEqualTo('/api/network-error'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('network-error-jwt')
                .whenScenarioStateIs('network-ok')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Network Recovered')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/network-error"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Network Recovered'

        and: 'no token refresh needed for network errors'
        wireMockServer.verify(0, postRequestedFor(urlMatching('/oauth/.*')))
    }

    def 'should work with POST requests and JWT refresh'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken(INITIAL_JWT)
                .withRefreshToken('post_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'POST API returns 401 with old token, 201 with new token'
        wireMockServer.stubFor(post(urlEqualTo('/api/create'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .withHeader('Content-Type', equalTo('application/json'))
                .inScenario('post-jwt-refresh')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized'))
                .willSetStateTo('post-token-refreshed'))

        wireMockServer.stubFor(post(urlEqualTo('/api/create'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .withHeader('Content-Type', equalTo('application/json'))
                .inScenario('post-jwt-refresh')
                .whenScenarioStateIs('post-token-refreshed')
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBody('{"id": 123, "status": "created"}')))

        and: 'token refresh endpoint'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("""
                        {
                            "access_token": "${REFRESHED_JWT}",
                            "token_type": "Bearer"
                        }
                        """.stripIndent())))

        and:
        def requestBody = '{"name": "Test Resource", "type": "example"}'
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/create"))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .header('Content-Type', 'application/json')
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 201
        response.body().contains('"id": 123')

        and:
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))
        wireMockServer.verify(2, postRequestedFor(urlEqualTo('/api/create'))
                .withRequestBody(equalTo(requestBody)))
    }

    def 'should preserve request body and headers through JWT refresh and retry'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken(INITIAL_JWT)
                .withRefreshToken('preserve_test_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'Complex flow: 401 -> refresh -> 503 -> retry -> success'
        def customHeaderValue = 'custom-value-12345'
        def requestBody = '{"data": "important payload", "timestamp": 1234567890}'

        wireMockServer.stubFor(put(urlEqualTo('/api/preserve'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .withHeader('X-Custom-Header', equalTo(customHeaderValue))
                .withHeader('Content-Type', equalTo('application/json'))
                .withRequestBody(equalTo(requestBody))
                .inScenario('preserve-flow')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized'))
                .willSetStateTo('preserve-token-refreshed'))

        wireMockServer.stubFor(put(urlEqualTo('/api/preserve'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .withHeader('X-Custom-Header', equalTo(customHeaderValue))
                .withHeader('Content-Type', equalTo('application/json'))
                .withRequestBody(equalTo(requestBody))
                .inScenario('preserve-flow')
                .whenScenarioStateIs('preserve-token-refreshed')
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody('Service Unavailable'))
                .willSetStateTo('preserve-retry-ok'))

        wireMockServer.stubFor(put(urlEqualTo('/api/preserve'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .withHeader('X-Custom-Header', equalTo(customHeaderValue))
                .withHeader('Content-Type', equalTo('application/json'))
                .withRequestBody(equalTo(requestBody))
                .inScenario('preserve-flow')
                .whenScenarioStateIs('preserve-retry-ok')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Update Successful')))

        and: 'token refresh endpoint'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Set-Cookie', "JWT=${REFRESHED_JWT}; Path=/; HttpOnly")))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/preserve"))
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .header('Content-Type', 'application/json')
                .header('X-Custom-Header', customHeaderValue)
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Update Successful'

        and: 'verify all requests maintained the same body and headers'
        wireMockServer.verify(3, putRequestedFor(urlEqualTo('/api/preserve'))
                .withHeader('X-Custom-Header', equalTo(customHeaderValue))
                .withHeader('Content-Type', equalTo('application/json'))
                .withRequestBody(equalTo(requestBody)))

        and: 'verify token refresh was called'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))
    }
}
