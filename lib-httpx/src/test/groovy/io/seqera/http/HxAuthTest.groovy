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

/**
 * Unit tests for HxAuth
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxAuthTest extends Specification {

    def 'should create auth with token only'() {
        when:
        def auth = HxAuth.of('my.jwt.token')

        then:
        auth.accessToken() == 'my.jwt.token'
        auth.refreshToken() == null
    }

    def 'should create auth with token and refresh'() {
        when:
        def auth = HxAuth.of('my.jwt.token', 'my-refresh-token')

        then:
        auth.accessToken() == 'my.jwt.token'
        auth.refreshToken() == 'my-refresh-token'
    }

    def 'should compute consistent key from token'() {
        given:
        def auth1 = HxAuth.of('my.jwt.token', 'refresh1')
        def auth2 = HxAuth.of('my.jwt.token', 'refresh2')
        def auth3 = HxAuth.of('different.jwt.token', 'refresh1')

        expect:
        HxAuth.key(auth1) == HxAuth.key(auth2)  // same token = same key
        HxAuth.key(auth1) != HxAuth.key(auth3)  // different token = different key
        HxAuth.key(auth1).length() == 64        // SHA-256 hex = 64 chars
    }

    def 'should return default key for null auth'() {
        expect:
        HxAuth.keyOrDefault(null, 'default') == 'default'
        HxAuth.keyOrDefault(HxAuth.of('token'), 'default') != 'default'
        HxAuth.keyOrDefault(null, 'custom') == 'custom'
    }

    def 'should reject null access token'() {
        when:
        HxAuth.of(null)

        then:
        thrown(IllegalArgumentException)
    }

    def 'should reject null access token with refresh'() {
        when:
        HxAuth.of(null, 'refresh')

        then:
        thrown(IllegalArgumentException)
    }

    def 'should mask tokens in toString'() {
        when:
        def auth = HxAuth.of('eyJhbGciOiJIUzI1NiJ9.payload.signature', 'my-refresh-token-value')

        then:
        !auth.toString().contains('eyJhbGciOiJIUzI1NiJ9.payload.signature')
        !auth.toString().contains('my-refresh-token-value')
        auth.toString().startsWith('HxAuth[')
    }

    def 'should implement equals and hashCode correctly'() {
        expect:
        HxAuth.of(token1, refresh1) == HxAuth.of(token2, refresh2) == expected
        (HxAuth.of(token1, refresh1).hashCode() == HxAuth.of(token2, refresh2).hashCode()) == expected

        where:
        token1          | refresh1   | token2          | refresh2   | expected
        'a.b.c'         | 'r1'       | 'a.b.c'         | 'r1'       | true
        'a.b.c'         | null       | 'a.b.c'         | null       | true
        'a.b.c'         | 'r1'       | 'a.b.c'         | 'r2'       | false
        'a.b.c'         | 'r1'       | 'x.y.z'         | 'r1'       | false
    }
}
