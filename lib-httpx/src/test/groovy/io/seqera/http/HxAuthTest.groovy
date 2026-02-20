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
        def auth = new DefaultHxAuth('my.jwt.token', null)

        then:
        auth.accessToken() == 'my.jwt.token'
        auth.refreshToken() == null
        auth.refreshUrl() == null
    }

    def 'should create auth with token and refresh'() {
        when:
        def auth = new DefaultHxAuth('my.jwt.token', 'my-refresh-token')

        then:
        auth.accessToken() == 'my.jwt.token'
        auth.refreshToken() == 'my-refresh-token'
        auth.refreshUrl() == null
    }

    def 'should create auth with token, refresh, and refreshUrl'() {
        when:
        def auth = new DefaultHxAuth('my.jwt.token', 'my-refresh-token', 'https://example.com/oauth/token')

        then:
        auth.accessToken() == 'my.jwt.token'
        auth.refreshToken() == 'my-refresh-token'
        auth.refreshUrl() == 'https://example.com/oauth/token'
    }

    def 'should assign unique stable key'() {
        given:
        def auth1 = new DefaultHxAuth('my.jwt.token', 'refresh1')
        def auth2 = new DefaultHxAuth('my.jwt.token', 'refresh2')

        expect:
        auth1.id() != auth2.id()  // each instance gets a unique key
        auth1.id() == auth1.withToken('new.jwt.token').id()  // key is stable across withToken
        auth1.id() == auth1.withRefresh('new-refresh').id()  // key is stable across withRefresh
    }

    def 'should reject null access token'() {
        when:
        new DefaultHxAuth(null, null)

        then:
        thrown(IllegalArgumentException)
    }

    def 'should reject null access token with refresh'() {
        when:
        new DefaultHxAuth(null, 'refresh')

        then:
        thrown(IllegalArgumentException)
    }

    def 'should mask tokens in toString'() {
        when:
        def auth = new DefaultHxAuth('eyJhbGciOiJIUzI1NiJ9.payload.signature', 'my-refresh-token-value')

        then:
        !auth.toString().contains('eyJhbGciOiJIUzI1NiJ9.payload.signature')
        !auth.toString().contains('my-refresh-token-value')
        auth.toString().startsWith('HxAuth[')
    }

    def 'each instance should have unique identity'() {
        given:
        def auth1 = new DefaultHxAuth('a.b.c', 'r1')
        def auth2 = new DefaultHxAuth('a.b.c', 'r1')

        expect:
        auth1.id() != auth2.id()  // different instances have different keys
        auth1.id() == auth1.withToken('x.y.z').id()  // key preserved after withToken
        auth1.id() == auth1.withRefresh('r2').id()    // key preserved after withRefresh
    }
}
