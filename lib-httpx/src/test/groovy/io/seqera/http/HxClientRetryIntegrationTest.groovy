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
import com.github.tomakehurst.wiremock.http.Fault
import spock.lang.Shared
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * Integration tests for HxClient retry functionality using WireMock
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientRetryIntegrationTest extends Specification {

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

    def 'should retry on 500 server error and eventually succeed'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(100))
                .build()
        def client = HxClient.create(config)

        and: 'server returns 500 twice, then 200'
        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .inScenario('retry-500')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody('Server Error'))
                .willSetStateTo('first-retry'))

        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .inScenario('retry-500')
                .whenScenarioStateIs('first-retry')
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody('Server Error'))
                .willSetStateTo('second-retry'))

        wireMockServer.stubFor(get(urlEqualTo('/api/data'))
                .inScenario('retry-500')
                .whenScenarioStateIs('second-retry')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Success!')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/data"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Success!'

        and: 'verify 3 requests were made'
        wireMockServer.verify(3, getRequestedFor(urlEqualTo('/api/data')))
    }

    def 'should retry on 503 service unavailable'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.create(config)

        and: 'server returns 503 once, then 200'
        wireMockServer.stubFor(get(urlEqualTo('/api/service'))
                .inScenario('retry-503')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody('Service Unavailable'))
                .willSetStateTo('retry-done'))

        wireMockServer.stubFor(get(urlEqualTo('/api/service'))
                .inScenario('retry-503')
                .whenScenarioStateIs('retry-done')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Service Available')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/service"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Service Available'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/service')))
    }

    def 'should retry on 429 rate limit'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(100))
                .build()
        def client = HxClient.create(config)

        and: 'server returns 429 with retry-after header, then 200'
        wireMockServer.stubFor(get(urlEqualTo('/api/throttled'))
                .inScenario('rate-limit')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader('Retry-After', '1')
                        .withBody('Too Many Requests'))
                .willSetStateTo('throttle-done'))

        wireMockServer.stubFor(get(urlEqualTo('/api/throttled'))
                .inScenario('rate-limit')
                .whenScenarioStateIs('throttle-done')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Request Processed')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/throttled"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Request Processed'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/throttled')))
    }

    def 'should not retry on 404 client error'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.create(config)

        and: 'server always returns 404'
        wireMockServer.stubFor(get(urlEqualTo('/api/notfound'))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withBody('Not Found')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/notfound"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 404
        response.body() == 'Not Found'

        and: 'only one request should be made (no retries)'
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/notfound')))
    }

    def 'should respect custom retry status codes'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(50))
                .withRetryStatusCodes([502] as Set) // only retry on 502
                .build()
        def client = HxClient.create(config)

        and: 'server returns 500 (which should NOT be retried with custom config)'
        wireMockServer.stubFor(get(urlEqualTo('/api/custom'))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody('Server Error')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/custom"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 500
        response.body() == 'Server Error'

        and: 'only one request should be made (500 not in custom retry codes)'
        wireMockServer.verify(1, getRequestedFor(urlEqualTo('/api/custom')))
    }

    def 'should exhaust all retry attempts and return final error'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.create(config)

        and: 'server always returns 503'
        wireMockServer.stubFor(get(urlEqualTo('/api/persistent-error'))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withBody('Service Unavailable')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/persistent-error"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 503
        response.body() == 'Service Unavailable'

        and: 'all 3 attempts should be made'
        wireMockServer.verify(3, getRequestedFor(urlEqualTo('/api/persistent-error')))
    }

    def 'should retry on connection errors'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.create(config)

        and: 'server returns connection error first, then success'
        wireMockServer.stubFor(get(urlEqualTo('/api/connection'))
                .inScenario('connection-error')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo('connection-ok'))

        wireMockServer.stubFor(get(urlEqualTo('/api/connection'))
                .inScenario('connection-error')
                .whenScenarioStateIs('connection-ok')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Connection OK')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/connection"))
                .GET()
                .build()

        when:
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        response.statusCode() == 200
        response.body() == 'Connection OK'
    }

    def 'should work with async requests and retry'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(100))
                .build()
        def client = HxClient.create(config)

        and: 'server returns 502 once, then 200'
        wireMockServer.stubFor(get(urlEqualTo('/api/async'))
                .inScenario('async-retry')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(502)
                        .withBody('Bad Gateway'))
                .willSetStateTo('async-ok'))

        wireMockServer.stubFor(get(urlEqualTo('/api/async'))
                .inScenario('async-retry')
                .whenScenarioStateIs('async-ok')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('Async Success')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/async"))
                .GET()
                .build()

        when:
        def future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        def response = future.get()

        then:
        response.statusCode() == 200
        response.body() == 'Async Success'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/async')))
    }
}
