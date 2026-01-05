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
        auth1.key() == auth2.key()  // same token = same key
        auth1.key() != auth3.key()  // different token = different key
        auth1.key().length() == 64  // SHA-256 hex = 64 chars
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
