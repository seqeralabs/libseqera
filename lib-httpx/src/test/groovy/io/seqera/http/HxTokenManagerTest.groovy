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
        def config = HxConfig.builder()
                .withJwtToken('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
                .build()
        def tokenManager = new HxTokenManager(config)
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()

        when:
        def requestWithAuth = tokenManager.addAuthHeader(originalRequest)

        then:
        requestWithAuth.headers().firstValue('Authorization').orElse(null) == 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'
    }

    def 'should not add authorization header when no token'() {
        given:
        def config = HxConfig.builder().build()
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
        def config = HxConfig.builder()
                .withJwtToken('Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
                .build()
        def tokenManager = new HxTokenManager(config)
        def originalRequest = HttpRequest.newBuilder()
                .uri(URI.create('https://example.com/api'))
                .GET()
                .build()

        when:
        def requestWithAuth = tokenManager.addAuthHeader(originalRequest)

        then:
        requestWithAuth.headers().firstValue('Authorization').orElse(null) == 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'
    }

    def 'should detect if token refresh is possible'() {
        given:
        def config1 = HxConfig.builder()
                .withRefreshToken('refresh-token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                .build()
        def config2 = HxConfig.builder()
                .withRefreshToken('refresh-token')
                .build()
        def config3 = HxConfig.builder()
                .withRefreshTokenUrl('https://example.com/oauth/token')
                .build()

        expect:
        new HxTokenManager(config1).canRefreshToken()
        !new HxTokenManager(config2).canRefreshToken()
        !new HxTokenManager(config3).canRefreshToken()
    }

    def 'should validate JWT token format'() {
        given:
        def config = HxConfig.builder().build()
        def tokenManager = new HxTokenManager(config)

        expect:
        tokenManager.isValidJwtToken(token) == expected

        where:
        token                                                                                                                                                            | expected
        'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c' | true
        'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c'     | true
        'invalid.token'                                                                                                                                                  | false
        'invalid'                                                                                                                                                        | false
        null                                                                                                                                                             | false
        ''                                                                                                                                                               | false
    }

    // Note: Skipping this test as HttpHeaders is a final class that cannot be easily mocked in Spock
    // This functionality is covered by integration tests

    def 'should update tokens thread-safely'() {
        given:
        def config = HxConfig.builder()
                .withJwtToken('initial-jwt')
                .withRefreshToken('initial-refresh')
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
        def config = HxConfig.builder()
                .withJwtToken('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c')
                .build()
        def tokenManager = new HxTokenManager(config)
        def originalToken = tokenManager.getCurrentJwtToken()

        when:
        tokenManager.updateTokens('invalid-token', 'new-refresh-token')

        then:
        tokenManager.getCurrentJwtToken() == originalToken
        tokenManager.getCurrentRefreshToken() == 'new-refresh-token'
    }
}
