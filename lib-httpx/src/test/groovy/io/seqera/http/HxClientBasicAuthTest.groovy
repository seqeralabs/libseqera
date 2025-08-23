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

import spock.lang.Specification

import java.net.http.HttpRequest

/**
 * Test cases for HTTP Basic Authentication support in HxClient.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxClientBasicAuthTest extends Specification {

    def "should add basic auth header to request"() {
        given:
        def config = HxConfig.newBuilder()
                .withBasicAuth("testuser", "testpass")
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .GET()
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        requestWithAuth != originalRequest
        requestWithAuth.headers().firstValue("Authorization").isPresent()
        
        and:
        def authHeader = requestWithAuth.headers().firstValue("Authorization").get()
        authHeader.startsWith("Basic ")
        
        and:
        def credentials = authHeader.substring("Basic ".length())
        def decoded = new String(Base64.getDecoder().decode(credentials))
        decoded == "testuser:testpass"
    }

    def "should support basic auth token directly"() {
        given:
        def config = HxConfig.newBuilder()
                .withBasicAuth("user123:pass456")
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .GET()
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        def authHeader = requestWithAuth.headers().firstValue("Authorization").get()
        def credentials = authHeader.substring("Basic ".length())
        def decoded = new String(Base64.getDecoder().decode(credentials))
        decoded == "user123:pass456"
    }

    def "should work with JWT token authentication separately"() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken("jwt.token.here")
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .GET()
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        def authHeader = requestWithAuth.headers().firstValue("Authorization").get()
        authHeader == "Bearer jwt.token.here"
        !authHeader.startsWith("Basic")
    }

    def "should use basic auth when only basic auth is configured"() {
        given:
        def config = HxConfig.newBuilder()
                .withBasicAuth("fallback-user", "fallback-pass")
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .GET()
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        def authHeader = requestWithAuth.headers().firstValue("Authorization").get()
        authHeader.startsWith("Basic ")
        
        and:
        def credentials = authHeader.substring("Basic ".length())
        def decoded = new String(Base64.getDecoder().decode(credentials))
        decoded == "fallback-user:fallback-pass"
    }

    def "should return original request when no auth configured"() {
        given:
        def client = HxClient.newHxClient()  // No auth configured
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .GET()
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        requestWithAuth == originalRequest
        !requestWithAuth.headers().firstValue("Authorization").isPresent()
    }

    def "should handle empty basic auth token"() {
        given:
        def config = HxConfig.newBuilder()
                .withBasicAuth("")  // Empty token
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .GET()
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        requestWithAuth == originalRequest  // No auth header added
        !requestWithAuth.headers().firstValue("Authorization").isPresent()
    }

    def "should handle empty credentials via username/password method"() {
        given:
        def config = HxConfig.newBuilder()
                .withBasicAuth("", "")  // Empty credentials
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        when:
        def hasBasicAuth = client.tokenManager.hasBasicAuth()
        
        then:
        !hasBasicAuth  // Should return false for empty credentials (results in ":" token)
    }

    def "should correctly identify when basic auth is configured"() {
        when:
        def config1 = HxConfig.newBuilder()
                .withBasicAuth("user", "pass")
                .build()
        def client1 = HxClient.newBuilder().config(config1).build()
        
        then:
        client1.tokenManager.hasBasicAuth()
        client1.tokenManager.basicAuthToken == "user:pass"
        
        when:
        def client2 = HxClient.newHxClient()  // No basic auth
        
        then:
        !client2.tokenManager.hasBasicAuth()
        client2.tokenManager.basicAuthToken == null
    }

    def "should preserve request properties when adding auth header"() {
        given:
        def config = HxConfig.newBuilder()
                .withBasicAuth("user", "pass")
                .build()
        def client = HxClient.newBuilder().config(config).build()
        
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://example.com/api"))
                .header("Content-Type", "application/json")
                .header("User-Agent", "test-client")
                .POST(HttpRequest.BodyPublishers.ofString('{"test": "data"}'))
                .build()
        
        when:
        def requestWithAuth = client.tokenManager.addAuthHeader(originalRequest)
        
        then:
        requestWithAuth.uri() == originalRequest.uri()
        requestWithAuth.method() == originalRequest.method()
        requestWithAuth.headers().firstValue("Content-Type").get() == "application/json"
        requestWithAuth.headers().firstValue("User-Agent").get() == "test-client"
        
        and:
        requestWithAuth.headers().firstValue("Authorization").isPresent()
        requestWithAuth.headers().firstValue("Authorization").get().startsWith("Basic ")
    }

    def "should reject configuration with both JWT and Basic auth"() {
        when:
        def config = HxConfig.newBuilder()
                .withBearerToken("jwt.token.here")
                .withBasicAuth("user", "pass")
                .build()
        
        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Cannot configure both JWT token and Basic authentication")
    }
}
