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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Shared
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * Integration tests for HxClient JWT token refresh functionality using WireMock
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientJwtIntegrationTest extends Specification {

    @Shared
    WireMockServer wireMockServer

    // Sample JWT tokens (these are not real, just for testing format)
    static final String INITIAL_JWT = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJpbml0aWFsIiwibmFtZSI6IkluaXRpYWwgVG9rZW4iLCJpYXQiOjE1MTYyMzkwMjJ9.signature1'
    static final String REFRESHED_JWT = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJyZWZyZXNoZWQiLCJuYW1lIjoiUmVmcmVzaGVkIFRva2VuIiwiaWF0IjoxNTE2MjM5MDIyfQ.signature2'
    static final String NEW_REFRESH_TOKEN = 'new_refresh_token_12345'

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

    def 'should refresh JWT token on 401 and retry request with new token'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken(INITIAL_JWT)
                .withRefreshToken('old_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .withMaxAttempts(3)
                .build()
        def client = HxClient.create(config)

        and: 'API returns 401 with old token, 200 with new token'
        wireMockServer.stubFor(get(urlEqualTo('/api/protected'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('jwt-refresh')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized'))
                .willSetStateTo('token-refreshed'))

        wireMockServer.stubFor(get(urlEqualTo('/api/protected'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .inScenario('jwt-refresh')
                .whenScenarioStateIs('token-refreshed')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Protected Data')))

        and: 'token refresh endpoint returns new tokens via cookies'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .withHeader('Content-Type', equalTo('application/x-www-form-urlencoded'))
                .withRequestBody(containing('grant_type=refresh_token'))
                .withRequestBody(containing('refresh_token=old_refresh_token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Set-Cookie', "JWT=${REFRESHED_JWT}; Path=/; HttpOnly")
                        .withHeader('Set-Cookie', "JWT_REFRESH_TOKEN=${NEW_REFRESH_TOKEN}; Path=/; HttpOnly")
                        .withBody('{"token_type":"Bearer"}')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/protected"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Protected Data'

        and: 'verify token refresh was called'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))

        and: 'verify both requests to protected endpoint'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/protected')))
    }

    def 'should handle JSON response format for token refresh'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken(INITIAL_JWT)
                .withRefreshToken('json_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .build()
        def client = HxClient.create(config)

        and: 'API returns 401, then 200 with refreshed token'
        wireMockServer.stubFor(get(urlEqualTo('/api/json-protected'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('jwt-json-refresh')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized'))
                .willSetStateTo('json-token-refreshed'))

        wireMockServer.stubFor(get(urlEqualTo('/api/json-protected'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .inScenario('jwt-json-refresh')  
                .whenScenarioStateIs('json-token-refreshed')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Protected JSON Data')))

        and: 'token refresh endpoint returns new tokens via JSON'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .withRequestBody(containing('grant_type=refresh_token'))
                .withRequestBody(containing('refresh_token=json_refresh_token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Content-Type', 'application/json')
                        .withBody("""
                        {
                            "access_token": "${REFRESHED_JWT}",
                            "refresh_token": "${NEW_REFRESH_TOKEN}",
                            "token_type": "Bearer",
                            "expires_in": 3600
                        }
                        """.stripIndent())))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/json-protected"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Protected JSON Data'

        and:
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/json-protected')))
    }

    def 'should not attempt refresh when no refresh token configured'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken(INITIAL_JWT)
                // No refresh token configured
                .build()
        def client = HxClient.create(config)

        and: 'API returns 401'
        wireMockServer.stubFor(get(urlEqualTo('/api/no-refresh'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/no-refresh"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 401
        response.body() == 'Unauthorized'

        and: 'no token refresh attempt should be made'
        wireMockServer.verify(0, postRequestedFor(urlMatching('/oauth/.*')))
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/no-refresh')))
    }

    def 'should handle token refresh failure gracefully'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken(INITIAL_JWT)
                .withRefreshToken('failing_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .build()
        def client = HxClient.create(config)

        and: 'API returns 401'
        wireMockServer.stubFor(get(urlEqualTo('/api/refresh-fail'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized')))

        and: 'token refresh endpoint returns error'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .withRequestBody(containing('refresh_token=failing_refresh_token'))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody('{"error": "invalid_grant"}')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/refresh-fail"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 401
        response.body() == 'Unauthorized'

        and: 'token refresh was attempted'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/refresh-fail')))
    }

    def 'should work with async requests and JWT refresh'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken(INITIAL_JWT)
                .withRefreshToken('async_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .build()
        def client = HxClient.create(config)

        and: 'API returns 401 with old token, 200 with new token'
        wireMockServer.stubFor(get(urlEqualTo('/api/async-protected'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .inScenario('async-jwt-refresh')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized'))
                .willSetStateTo('async-token-refreshed'))

        wireMockServer.stubFor(get(urlEqualTo('/api/async-protected'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .inScenario('async-jwt-refresh')
                .whenScenarioStateIs('async-token-refreshed')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Async Protected Data')))

        and: 'token refresh endpoint'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .withRequestBody(containing('refresh_token=async_refresh_token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader('Set-Cookie', "JWT=${REFRESHED_JWT}; Path=/; HttpOnly")
                        .withHeader('Set-Cookie', "JWT_REFRESH_TOKEN=${NEW_REFRESH_TOKEN}; Path=/; HttpOnly")))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/async-protected"))
                .GET()
                .build()

        when:
        def future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        def response = future.get()

        then:
        response.statusCode() == 200
        response.body() == 'Async Protected Data'

        and:
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/async-protected')))
    }

    def 'should handle concurrent requests with token refresh'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken(INITIAL_JWT)
                .withRefreshToken('concurrent_refresh_token')
                .withRefreshTokenUrl("http://localhost:${wireMockServer.port()}/oauth/token")
                .build()
        def client = HxClient.create(config)

        and: 'API returns 401 for initial requests, then 200 with refreshed token'
        wireMockServer.stubFor(get(urlEqualTo('/api/concurrent'))
                .withHeader('Authorization', equalTo("Bearer ${INITIAL_JWT}"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody('Unauthorized')))

        wireMockServer.stubFor(get(urlEqualTo('/api/concurrent'))
                .withHeader('Authorization', equalTo("Bearer ${REFRESHED_JWT}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Concurrent Success')))

        and: 'token refresh endpoint with slight delay to test concurrency'
        wireMockServer.stubFor(post(urlEqualTo('/oauth/token'))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(100) // Small delay to simulate network latency
                        .withHeader('Set-Cookie', "JWT=${REFRESHED_JWT}; Path=/; HttpOnly")
                        .withHeader('Set-Cookie', "JWT_REFRESH_TOKEN=${NEW_REFRESH_TOKEN}; Path=/; HttpOnly")))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/concurrent"))
                .GET()
                .build()

        when: 'send multiple concurrent requests'
        def futures = (1..3).collect { 
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        }
        def responses = futures.collect { it.get() }

        then: 'all requests should succeed after token refresh'
        responses.every { it.statusCode() == 200 }
        responses.every { it.body() == 'Concurrent Success' }

        and: 'token refresh should be called only once despite multiple 401s'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo('/oauth/token')))
    }

    def 'should add Bearer prefix to JWT token automatically'() {
        given:
        def tokenWithoutBearer = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0IiwibmFtZSI6IlRlc3QiLCJpYXQiOjE1MTYyMzkwMjJ9.signature'
        def config = HxConfig.builder()
                .withJwtToken(tokenWithoutBearer)
                .build()
        def client = HxClient.create(config)

        and: 'API expects Bearer prefix'
        wireMockServer.stubFor(get(urlEqualTo('/api/bearer-test'))
                .withHeader('Authorization', equalTo("Bearer ${tokenWithoutBearer}"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Bearer Added')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/bearer-test"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Bearer Added'
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/bearer-test'))
                .withHeader('Authorization', equalTo("Bearer ${tokenWithoutBearer}")))
    }

    def 'should preserve existing Bearer prefix in JWT token'() {
        given:
        def tokenWithBearer = "Bearer ${INITIAL_JWT}"
        def config = HxConfig.builder()
                .withJwtToken(tokenWithBearer)
                .build()
        def client = HxClient.create(config)

        and: 'API expects Bearer prefix (should not be doubled)'
        wireMockServer.stubFor(get(urlEqualTo('/api/bearer-preserve'))
                .withHeader('Authorization', equalTo(tokenWithBearer))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Bearer Preserved')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/bearer-preserve"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Bearer Preserved'
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/bearer-preserve'))
                .withHeader('Authorization', equalTo(tokenWithBearer)))
    }
}
