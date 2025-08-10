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

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import io.seqera.util.retry.Retryable
import spock.lang.Specification

/**
 * Unit tests for HxClient
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientTest extends Specification {

    def 'should create client with default config'() {
        when:
        def client = HxClient.create()

        then:
        client.config != null
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client with custom config'() {
        given:
        def config = HxConfig.builder()
                .withMaxAttempts(3)
                .withDelay(Duration.ofMillis(100))
                .build()

        when:
        def client = HxClient.create(config)

        then:
        client.config.maxAttempts == 3
        client.config.delay == Duration.ofMillis(100)
    }

    def 'should create client with custom HttpClient and config'() {
        given:
        def httpClient = HttpClient.newHttpClient()
        def config = HxConfig.builder().withMaxAttempts(2).build()

        when:
        def client = HxClient.create(httpClient, config)

        then:
        client.httpClient == httpClient
        client.config.maxAttempts == 2
    }

    def 'should identify retryable exceptions'() {
        given:
        def client = HxClient.create()

        expect:
        client.shouldRetryOnException(new IOException('Connection timeout'))
        client.shouldRetryOnException(new ConnectException('Connection refused'))
        !client.shouldRetryOnException(new IllegalArgumentException('Bad argument'))
        !client.shouldRetryOnException(new RuntimeException('Runtime error'))
    }

    def 'should identify retryable HTTP status codes'() {
        given:
        def client = HxClient.create()
        def response429 = Mock(HttpResponse) { statusCode() >> 429 }
        def response500 = Mock(HttpResponse) { statusCode() >> 500 }
        def response502 = Mock(HttpResponse) { statusCode() >> 502 }
        def response503 = Mock(HttpResponse) { statusCode() >> 503 }
        def response504 = Mock(HttpResponse) { statusCode() >> 504 }
        def response200 = Mock(HttpResponse) { statusCode() >> 200 }
        def response404 = Mock(HttpResponse) { statusCode() >> 404 }

        expect:
        client.shouldRetryOnResponse(response429)
        client.shouldRetryOnResponse(response500)
        client.shouldRetryOnResponse(response502)
        client.shouldRetryOnResponse(response503)
        client.shouldRetryOnResponse(response504)
        !client.shouldRetryOnResponse(response200)
        !client.shouldRetryOnResponse(response404)
    }

    def 'should use custom retry status codes'() {
        given:
        def config = HxConfig.builder()
                .withRetryStatusCodes([429, 503] as Set)
                .build()
        def client = HxClient.create(config)
        def response429 = Mock(HttpResponse) { statusCode() >> 429 }
        def response500 = Mock(HttpResponse) { statusCode() >> 500 }
        def response503 = Mock(HttpResponse) { statusCode() >> 503 }

        expect:
        client.shouldRetryOnResponse(response429)
        !client.shouldRetryOnResponse(response500)
        client.shouldRetryOnResponse(response503)
    }

    def 'should create client from Retryable.Config'() {
        given:
        def retryableConfig = Retryable.ofDefaults().config()

        when:
        def client = HxClient.create(retryableConfig)

        then:
        client.config != null
        client.config.delay == retryableConfig.delay
        client.config.maxDelay == retryableConfig.maxDelay
        client.config.maxAttempts == retryableConfig.maxAttempts
        client.config.jitter == retryableConfig.jitter
        client.config.multiplier == retryableConfig.multiplier
        client.config.retryStatusCodes == [429, 500, 502, 503, 504] as Set // HTTP defaults
    }

    def 'should create client with custom HttpClient from Retryable.Config'() {
        given:
        def httpClient = HttpClient.newHttpClient()
        def retryableConfig = Retryable.ofDefaults().config()

        when:
        def client = HxClient.create(httpClient, retryableConfig)

        then:
        client.httpClient == httpClient
        client.config.delay == retryableConfig.delay
        client.config.maxDelay == retryableConfig.maxDelay
        client.config.maxAttempts == retryableConfig.maxAttempts
        client.config.jitter == retryableConfig.jitter
        client.config.multiplier == retryableConfig.multiplier
    }

    def 'should handle null Retryable.Config'() {
        when:
        def client = HxClient.create((Retryable.Config) null)

        then:
        client.config != null
        client.config.delay == Duration.ofMillis(500)
        client.config.maxDelay == Duration.ofSeconds(30)
        client.config.maxAttempts == 5
    }

    def 'should send request successfully without retry'() {
        given:
        def mockHttpClient = Mock(HttpClient)
        def config = HxConfig.builder().build()
        def client = HxClient.create(mockHttpClient, config)
        def request = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()
        def responseBodyHandler = HttpResponse.BodyHandlers.ofString()
        def mockResponse = Mock(HttpResponse) {
            statusCode() >> 200
            body() >> 'success'
        }

        when:
        def response = client.send(request, responseBodyHandler)

        then:
        1 * mockHttpClient.send(_, responseBodyHandler) >> mockResponse
        response.statusCode() == 200
        response.body() == 'success'
    }

    // Note: Skipping async test due to complexity with CompletableFuture mocking
    // This functionality is covered by integration tests

    def 'should add auth header when JWT token is present'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
                .build()
        def mockHttpClient = Mock(HttpClient)
        def client = HxClient.create(mockHttpClient, config)
        def request = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()
        def responseBodyHandler = HttpResponse.BodyHandlers.ofString()
        def mockResponse = Mock(HttpResponse) {
            statusCode() >> 200
            body() >> 'success'
        }

        when:
        def response = client.send(request, responseBodyHandler)

        then:
        1 * mockHttpClient.send({ HttpRequest req ->
            req.headers().firstValue('Authorization').orElse('') == 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'
        }, responseBodyHandler) >> mockResponse
        response.statusCode() == 200
    }
}
