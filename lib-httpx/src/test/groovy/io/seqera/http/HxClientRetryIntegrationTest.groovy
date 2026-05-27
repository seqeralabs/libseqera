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

import static com.github.tomakehurst.wiremock.client.WireMock.*

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import spock.lang.Shared
import spock.lang.Specification
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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(100))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        // Cap maxDelay so the server-supplied Retry-After: 1 is bounded — this test verifies
        // the retry mechanic in general, not Retry-After honoring (covered separately below).
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(100))
                .withMaxDelay(Duration.ofMillis(200))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(50))
                .withRetryStatusCodes([502] as Set) // only retry on 502
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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
        def config = HxConfig.newBuilder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(100))
                .build()
        def client = HxClient.newBuilder().config(config).build()

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

    def 'should throw IOException when persistent connection errors occur'() {
        given:
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'server always returns connection reset error'
        wireMockServer.stubFor(get(urlEqualTo('/api/persistent-io-error'))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/persistent-io-error"))
                .GET()
                .build()

        when:
        client.send(request, HttpResponse.BodyHandlers.ofString())

        then:
        thrown(IOException)

        and: 'all 3 attempts should be made'
        wireMockServer.verify(3, getRequestedFor(urlEqualTo('/api/persistent-io-error')))
    }

    def 'should throw IOException when persistent connection errors occur with sendAsync'() {
        given:
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'server always returns connection reset error'
        wireMockServer.stubFor(get(urlEqualTo('/api/persistent-io-error-async'))
                .willReturn(aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/persistent-io-error-async"))
                .GET()
                .build()

        when:
        client
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .get()

        then:
        // The async method should throw an ExecutionException wrapping an IOException
        def ex = thrown(ExecutionException)
        // The underlying cause should be an IOException or its subclass (like SocketException)
        ex.cause instanceof IOException

        and: 'all 3 attempts should be made'
        wireMockServer.verify(3, getRequestedFor(urlEqualTo('/api/persistent-io-error-async')))
    }

    def 'should honour Retry-After header on 429 as a delay lower bound'() {
        given: 'fast backoff (50ms) but server requests a 1s wait via Retry-After'
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .withMaxDelay(Duration.ofSeconds(5))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'server returns 429 with Retry-After: 1, then 200'
        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited'))
                .inScenario('retry-after')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader('Retry-After', '1')
                        .withBody('{"type":"TOO_MANY_REQUESTS"}'))
                .willSetStateTo('hint-honored'))

        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited'))
                .inScenario('retry-after')
                .whenScenarioStateIs('hint-honored')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('OK')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/rate-limited"))
                .GET()
                .build()

        when:
        def t0 = System.currentTimeMillis()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())
        def elapsed = System.currentTimeMillis() - t0

        then:
        response.statusCode() == 200
        response.body() == 'OK'

        and: '2 attempts were made'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/rate-limited')))

        and: 'the retry honoured Retry-After (~1s), not the 50ms backoff (allow 25% jitter)'
        elapsed >= 750
    }

    def 'async send honours Retry-After header on 429'() {
        given: 'fast backoff but server requests a 1s wait via Retry-After'
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(50))
                .withMaxDelay(Duration.ofSeconds(5))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'server returns 429 with Retry-After: 1, then 200'
        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited-async'))
                .inScenario('retry-after-async')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader('Retry-After', '1')
                        .withBody('Too Many Requests'))
                .willSetStateTo('hint-honored'))

        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited-async'))
                .inScenario('retry-after-async')
                .whenScenarioStateIs('hint-honored')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('OK')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/rate-limited-async"))
                .GET()
                .build()

        when:
        def t0 = System.currentTimeMillis()
        def response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get()
        def elapsed = System.currentTimeMillis() - t0

        then:
        response.statusCode() == 200
        response.body() == 'OK'

        and: '2 attempts were made'
        wireMockServer.verify(2, getRequestedFor(urlEqualTo('/api/rate-limited-async')))

        and: 'the async retry honoured Retry-After (~1s ± 25% jitter), not the 50ms backoff'
        elapsed >= 750
    }

    def 'Retry-After is capped by the configured maxDelay'() {
        given: 'maxDelay (300ms) much smaller than the server-supplied Retry-After (10s)'
        def config = HxConfig.newBuilder()
                .withMaxAttempts(2)
                .withDelay(Duration.ofMillis(50))
                .withMaxDelay(Duration.ofMillis(300))
                .build()
        def client = HxClient.newBuilder().config(config).build()

        and: 'server returns 429 with an aggressive Retry-After'
        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited-capped'))
                .inScenario('retry-after-capped')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader('Retry-After', '10')   // 10s — far exceeds maxDelay
                        .withBody('Too Many Requests'))
                .willSetStateTo('done'))

        wireMockServer.stubFor(get(urlEqualTo('/api/rate-limited-capped'))
                .inScenario('retry-after-capped')
                .whenScenarioStateIs('done')
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody('OK')))

        and:
        def request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${wireMockServer.port()}/api/rate-limited-capped"))
                .GET()
                .build()

        when:
        def t0 = System.currentTimeMillis()
        def response = client.send(request, HttpResponse.BodyHandlers.ofString())
        def elapsed = System.currentTimeMillis() - t0

        then:
        response.statusCode() == 200

        and: 'wait was bounded by maxDelay (300ms ± 25% jitter), never the 10s hint'
        elapsed < 2000
    }
}
