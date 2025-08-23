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


import io.seqera.http.auth.AuthenticationCallback
import io.seqera.http.auth.AuthenticationScheme
import io.seqera.http.auth.WwwAuthenticateParser
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Integration test for HxClient WWW-Authenticate handling using real endpoints.
 * 
 * <p>This test verifies that HxClient can properly handle 401 responses with
 * WWW-Authenticate headers from real-world services, specifically testing
 * against container registry endpoints that require authentication.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientWwwAuthIntegrationTest extends Specification {

    private static final String TEST_URL = "https://public.cr.seqera.io/v2/nextflow/plugin/nf-amazon/blobs/sha256:bbc79350c844eba39e08a908056ce238a534b04cde7628767059d874bf725d72"

    @Timeout(30)
    def "should automatically handle WWW-Authenticate challenge when enabled"() {
        given: "HxClient configured for WWW-Authenticate handling"
        def config = HxConfig.newBuilder()
                .withWwwAuthentication(true)
                .build()

        and: "HttpClient configured to not follow redirects"
        def httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        def client = HxClient.newBuilder().httpClient(httpClient).config(config).build()

        when: "making request to endpoint that requires authentication"
        def request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: "should automatically handle authentication and return success status (200 or 307 redirect)"
        response != null
        response.statusCode() == 307 // 307 is redirect after successful auth
        response.headers().firstValue('Location').get() == "https://public-cr-prod.seqera.io/docker/registry/v2/blobs/sha256/bb/bbc79350c844eba39e08a908056ce238a534b04cde7628767059d874bf725d72/data"
    }

    @Timeout(30)
    def "should handle WWW-Authenticate challenge with callback providing credentials"() {
        given: "Authentication callback that provides test credentials"
        def callback = { scheme, realm ->
            if (scheme == AuthenticationScheme.BASIC) {
                // Return base64 encoded test:test
                return Base64.getEncoder().encodeToString("test:test".getBytes())
            }
            return null
        } as AuthenticationCallback

        and: "HxClient configured with authentication callback"
        def config = HxConfig.newBuilder()
                .withWwwAuthentication(true)
                .withWwwAuthenticationCallback(callback)
                .build()

        and: "HttpClient configured to not follow redirects"
        def httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        def client = HxClient.newBuilder().httpClient(httpClient).config(config).build()

        when: "making request to endpoint that requires authentication"
        def request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: "should receive a response with successful authentication"
        response != null
        response.statusCode() in [200, 307] // Various possible outcomes - 307 indicates successful auth with redirect

        cleanup:
        println "Response status: ${response?.statusCode()}"
        println "Response headers: ${response?.headers()?.map()}"
    }

    @Timeout(30)
    def "should not handle WWW-Authenticate when feature is disabled"() {
        given: "HxClient with WWW-Authenticate handling disabled"
        def config = HxConfig.newBuilder()
                .withWwwAuthentication(false) // Explicitly disabled
                .build()

        and: "HttpClient configured to not follow redirects"
        def httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        def client = HxClient.newBuilder().httpClient(httpClient).config(config).build()

        when: "making request to endpoint that requires authentication"
        def request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: "should receive 401 response without retry attempts"
        response != null
        response.statusCode() == 401
        
        and: "should have WWW-Authenticate headers"
        def wwwAuthHeaders = response.headers().allValues("WWW-Authenticate")
        wwwAuthHeaders.size() > 0

        cleanup:
        println "Response status: ${response?.statusCode()}"
        println "WWW-Authenticate headers: ${response?.headers()?.allValues('WWW-Authenticate')}"
    }

    def "should parse WWW-Authenticate headers from real response"() {
        given: "Direct HTTP client to get raw response"
        def httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        when: "making request to get 401 with WWW-Authenticate"
        def request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

        def response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        then: "should receive 401 with WWW-Authenticate headers"
        response.statusCode() == 401
        
        def wwwAuthHeaders = response.headers().allValues("WWW-Authenticate")
        wwwAuthHeaders.size() > 0

        when: "parsing the WWW-Authenticate headers"
        def allChallenges = []
        wwwAuthHeaders.each { headerValue ->
            def challenges = WwwAuthenticateParser.parse(headerValue)
            allChallenges.addAll(challenges)
        }

        then: "should successfully parse authentication challenges"
        allChallenges.size() > 0
        
        and: "should contain expected authentication schemes"
        def schemes = allChallenges.collect { it.scheme }
        schemes.any { it in [AuthenticationScheme.BASIC, AuthenticationScheme.BEARER] }

        cleanup:
        println "WWW-Authenticate headers: ${wwwAuthHeaders}"
        println "Parsed challenges: ${allChallenges}"
    }

    @Unroll
    def "should handle different authentication callback results: #scenario"() {
        given: "Authentication callback with specific behavior"
        def callback = callbackBehavior as AuthenticationCallback

        and: "HxClient configured with callback"
        def config = HxConfig.newBuilder()
                .withWwwAuthentication(true)
                .withWwwAuthenticationCallback(callback)
                .build()

        and: "HttpClient configured to not follow redirects"
        def httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build()

        def client = HxClient.newBuilder().httpClient(httpClient).config(config).build()

        when: "making request"
        def request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build()

        def response = client.send(request, HttpResponse.BodyHandlers.ofString())

        then: "should receive a response without throwing exceptions"
        response != null
        response.statusCode() == expectedStatus

        where:
        scenario           | callbackBehavior                                                      | expectedStatus // comment
        "returns null"     | { scheme, realm -> null }                                             | 307            // Falls back to anonymous auth, succeeds
        "throws exception" | { scheme, realm -> throw new RuntimeException("Callback error") }     | 307            // Falls back to anonymous auth after callback error, succeeds
        "returns empty"    | { scheme, realm -> "" }                                               | 401            // Empty credentials fail authentication
        "returns invalid"  | { scheme, realm -> "invalid-credentials" }                            | 401            // Invalid credentials fail authentication
    }
}
