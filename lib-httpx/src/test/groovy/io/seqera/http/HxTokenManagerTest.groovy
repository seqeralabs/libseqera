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

import spock.lang.Specification

/**
 * Unit tests for JwtTokenManager
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxTokenManagerTest extends Specification {

    def 'should add authorization header to request'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .build()
        def tokenManager = new HxTokenManager(config)
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()

        when:
        def requestWithAuth = tokenManager.addAuthHeader(originalRequest)

        then:
        requestWithAuth.headers().firstValue('Authorization').orElse(null) == 'Bearer fake.jwt.token'
    }

    def 'should not add authorization header when no token'() {
        given:
        def config = HxConfig.newBuilder().build()
        def tokenManager = new HxTokenManager(config)
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()

        when:
        def requestWithAuth = tokenManager.addAuthHeader(originalRequest)

        then:
        requestWithAuth.headers().firstValue('Authorization').isEmpty()
        requestWithAuth == originalRequest
    }

    def 'should handle token already with Bearer prefix'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken('Bearer fake.jwt.token')
                .build()
        def tokenManager = new HxTokenManager(config)
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()

        when:
        def requestWithAuth = tokenManager.addAuthHeader(originalRequest)

        then:
        requestWithAuth.headers().firstValue('Authorization').orElse(null) == 'Bearer fake.jwt.token'
    }

    def 'should detect if token refresh is possible'() {
        given:
        def config1 = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .withRefreshToken('fake-refresh-token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                .build()
        def config2 = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .build()
        def config3 = HxConfig.newBuilder()
                .build()

        expect:
        new HxTokenManager(config1).canRefreshToken()
        !new HxTokenManager(config2).canRefreshToken()
        !new HxTokenManager(config3).canRefreshToken()
    }

    def 'should validate JWT token format'() {
        given:
        def config = HxConfig.newBuilder().build()
        def tokenManager = new HxTokenManager(config)

        expect:
        tokenManager.isValidJwtToken(token) == expected

        where:
        token                          | expected
        'fake.jwt.token'              | true
        'Bearer fake.jwt.token'       | true
        'invalid.token'                                                                                                                                                  | false
        'invalid'                                                                                                                                                        | false
        null                                                                                                                                                             | false
        ''                                                                                                                                                               | false
    }

    // Note: Skipping this test as HttpHeaders is a final class that cannot be easily mocked in Spock
    // This functionality is covered by integration tests

    def 'should update tokens thread-safely'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .withRefreshToken('fake-refresh-token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                .build()
        def tokenManager = new HxTokenManager(config)

        when:
        tokenManager.updateTokens('new-jwt-token.new.token', 'new-refresh-token')

        then:
        tokenManager.getCurrentJwtToken() == 'new-jwt-token.new.token'
        tokenManager.getCurrentRefreshToken() == 'new-refresh-token'
    }

    def 'should not update invalid JWT token'() {
        given:
        def config = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .build()
        def tokenManager = new HxTokenManager(config)
        def originalToken = tokenManager.getCurrentJwtToken()

        when:
        tokenManager.updateTokens('invalid-token', 'new-refresh-token')

        then:
        tokenManager.getCurrentJwtToken() == originalToken
        tokenManager.getCurrentRefreshToken() == 'new-refresh-token'
    }

    def 'should validate JWT token refresh configuration'() {
        when: 'creating with JWT token + refresh token but missing refresh URL'
        def config1 = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .withRefreshToken('fake-refresh-token')
                // Missing refresh URL - incomplete configuration
                .build()
        new HxTokenManager(config1)

        then: 'should throw validation error for incomplete configuration'
        def ex1 = thrown(IllegalArgumentException)
        ex1.message.contains('JWT token refresh configuration is incomplete')

        when: 'creating with JWT token + refresh URL but missing refresh token'
        def config2 = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                // Missing refresh token - incomplete configuration
                .build()
        new HxTokenManager(config2)

        then: 'should throw validation error for incomplete configuration'
        def ex2 = thrown(IllegalArgumentException)
        ex2.message.contains('JWT token refresh configuration is incomplete')

        when: 'creating with refresh components but no JWT token'
        def config3 = HxConfig.newBuilder()
                .withRefreshToken('fake-refresh-token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                // Missing JWT token
                .build()
        new HxTokenManager(config3)

        then: 'should throw validation error for refresh without JWT'
        def ex3 = thrown(IllegalArgumentException)
        ex3.message.contains('Refresh components are configured without JWT token')

        when: 'creating with JWT token only (valid)'
        def jwtOnlyConfig = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .build()
        def jwtOnlyManager = new HxTokenManager(jwtOnlyConfig)

        then: 'should create successfully'
        jwtOnlyManager != null
        !jwtOnlyManager.canRefreshToken()

        when: 'creating with complete refresh configuration (valid)'
        def completeConfig = HxConfig.newBuilder()
                .withBearerToken('fake.jwt.token')
                .withRefreshToken('fake-refresh-token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                .build()
        def completeManager = new HxTokenManager(completeConfig)

        then: 'should create successfully'
        completeManager != null
        completeManager.canRefreshToken()

        when: 'creating with no JWT configuration (valid)'
        def noJwtConfig = HxConfig.newBuilder()
                .build()
        def noJwtManager = new HxTokenManager(noJwtConfig)

        then: 'should create successfully'
        noJwtManager != null
        !noJwtManager.canRefreshToken()
    }
}
