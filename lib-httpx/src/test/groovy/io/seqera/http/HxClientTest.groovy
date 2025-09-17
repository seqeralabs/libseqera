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

import java.net.CookiePolicy
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


    def 'should identify retryable exceptions'() {
        given:
        def client = HxClient.newHxClient()

        expect:
        client.shouldRetryOnException(new IOException('Connection timeout'))
        client.shouldRetryOnException(new ConnectException('Connection refused'))
        !client.shouldRetryOnException(new IllegalArgumentException('Bad argument'))
        !client.shouldRetryOnException(new RuntimeException('Runtime error'))
    }

    def 'should identify retryable HTTP status codes'() {
        given:
        def client = HxClient.newHxClient()
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
        def config = HxConfig.newBuilder()
                .retryStatusCodes([429, 503] as Set)
                .build()
        def client = HxClient.newBuilder().config(config).build()
        def response429 = Mock(HttpResponse) { statusCode() >> 429 }
        def response500 = Mock(HttpResponse) { statusCode() >> 500 }
        def response503 = Mock(HttpResponse) { statusCode() >> 503 }

        expect:
        client.shouldRetryOnResponse(response429)
        !client.shouldRetryOnResponse(response500)
        client.shouldRetryOnResponse(response503)
    }


    def 'should send request successfully without retry'() {
        given:
        def mockHttpClient = Mock(HttpClient)
        def config = HxConfig.newBuilder().build()
        def client = HxClient.newBuilder().httpClient(mockHttpClient).config(config).build()
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
        def config = HxConfig.newBuilder()
                .withBearerToken('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
                .build()
        def mockHttpClient = Mock(HttpClient)
        def client = HxClient.newBuilder().httpClient(mockHttpClient).config(config).build()
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

    def 'should create client using newHxClient with default settings'() {
        when:
        def client = HxClient.newHxClient()

        then:
        client.config != null
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with default settings'() {
        when:
        def client = HxClient.newBuilder().build()

        then:
        client.config != null
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should verify newHxClient is equivalent to newBuilder().build()'() {
        when:
        def client1 = HxClient.newHxClient()
        def client2 = HxClient.newBuilder().build()

        then:
        client1.config.maxAttempts == client2.config.maxAttempts
        client1.config.delay == client2.config.delay
        client1.config.maxDelay == client2.config.maxDelay
        client1.config.jitter == client2.config.jitter
        client1.config.multiplier == client2.config.multiplier
        client1.config.retryStatusCodes == client2.config.retryStatusCodes
    }

    def 'should create client using newBuilder with custom HttpClient'() {
        given:
        def customHttpClient = HttpClient.newHttpClient()

        when:
        def client = HxClient.newBuilder()
                .httpClient(customHttpClient)
                .build()

        then:
        client.httpClient == customHttpClient
        client.config != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with bearer token'() {
        given:
        def token = 'test-bearer-token'

        when:
        def client = HxClient.newBuilder()
                .bearerToken(token)
                .build()

        then:
        client.config.jwtToken == token
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with basic auth'() {
        given:
        def username = 'testuser'
        def password = 'testpass'

        when:
        def client = HxClient.newBuilder()
                .basicAuth(username, password)
                .build()

        then:
        client.config.basicAuthToken == 'testuser:testpass'
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with basic auth token'() {
        given:
        def token = 'myuser:mypass'

        when:
        def client = HxClient.newBuilder()
                .basicAuth(token)
                .build()

        then:
        client.config.basicAuthToken == 'myuser:mypass'
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with retry configuration'() {
        when:
        def client = HxClient.newBuilder()
                .maxAttempts(5)
                .retryDelay(Duration.ofSeconds(2))
                .build()

        then:
        client.config.maxAttempts == 5
        client.config.delay == Duration.ofSeconds(2)
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with HTTP client settings'() {
        when:
        def client = HxClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_2)
                .build()

        then:
        client.httpClient != null
        client.config != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with full configuration'() {
        when:
        def client = HxClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .bearerToken('jwt-token')
                .refreshToken('refresh-token')
                .refreshTokenUrl('https://auth.example.com/token')
                .maxAttempts(3)
                .retryDelay(Duration.ofMillis(500))
                .build()

        then:
        client.config.jwtToken == 'jwt-token'
        client.config.refreshToken == 'refresh-token'
        client.config.refreshTokenUrl == 'https://auth.example.com/token'
        client.config.maxAttempts == 3
        client.config.delay == Duration.ofMillis(500)
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should create client using newBuilder with retryConfig'() {
        given:
        def retryConfig = Retryable.ofDefaults().config()

        when:
        def client = HxClient.newBuilder()
                .retryConfig(retryConfig)
                .bearerToken('test-token')
                .build()

        then:
        client.config.delay == retryConfig.delay
        client.config.maxDelay == retryConfig.maxDelay
        client.config.maxAttempts == retryConfig.maxAttempts
        client.config.jitter == retryConfig.jitter
        client.config.multiplier == retryConfig.multiplier
        client.config.jwtToken == 'test-token'
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should handle null retryConfig gracefully'() {
        when:
        def client = HxClient.newBuilder()
                .retryConfig(null)
                .bearerToken('test-token')
                .build()

        then:
        client.config != null
        client.config.jwtToken == 'test-token'
        client.httpClient != null
        client.tokenManager != null
    }

    def 'should override retryConfig with individual settings'() {
        given:
        def retryConfig = Retryable.ofDefaults().config()

        when:
        def client = HxClient.newBuilder()
                .retryConfig(retryConfig)
                .maxAttempts(10)  // Override the retryConfig setting
                .retryDelay(Duration.ofSeconds(2))  // Override the retryConfig setting
                .build()

        then:
        // Individual settings should override retryConfig values
        client.config.maxAttempts == 10
        client.config.delay == Duration.ofSeconds(2)
        // Other retryConfig values should still be used
        client.config.maxDelay == retryConfig.maxDelay
        client.config.jitter == retryConfig.jitter
        client.config.multiplier == retryConfig.multiplier
    }
    
    def 'should configure refreshCookiePolicy via builder'() {
        when:
        def client = HxClient.newBuilder()
                .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
                .bearerToken('test-token')
                .build()
        
        then:
        client.config.refreshCookiePolicy == CookiePolicy.ACCEPT_ALL
        client.config.jwtToken == 'test-token'
        client.httpClient != null
        client.tokenManager != null
        client.tokenManager.cookieManager != null
    }
    
    def 'should handle null refreshCookiePolicy via builder'() {
        when:
        def client = HxClient.newBuilder()
                .refreshCookiePolicy(null)
                .bearerToken('test-token')
                .build()
        
        then:
        client.config.refreshCookiePolicy == null
        client.config.jwtToken == 'test-token'
        client.httpClient != null
        client.tokenManager != null
        client.tokenManager.cookieManager != null
    }
    
    def 'should configure different cookie policies via builder'() {
        expect:
        def client1 = HxClient.newBuilder()
                .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
                .build()
        client1.config.refreshCookiePolicy == CookiePolicy.ACCEPT_ALL
        
        def client2 = HxClient.newBuilder()
                .refreshCookiePolicy(CookiePolicy.ACCEPT_NONE)
                .build()
        client2.config.refreshCookiePolicy == CookiePolicy.ACCEPT_NONE
        
        def client3 = HxClient.newBuilder()
                .refreshCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                .build()
        client3.config.refreshCookiePolicy == CookiePolicy.ACCEPT_ORIGINAL_SERVER
    }
}
