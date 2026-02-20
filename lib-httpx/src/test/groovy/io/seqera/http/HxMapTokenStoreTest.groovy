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
 * Unit tests for HxMapTokenStore
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxMapTokenStoreTest extends Specification {

    def 'should store and retrieve auth by key'() {
        given:
        def store = new HxMapTokenStore()
        def auth = new DefaultHxAuth('my.jwt.token', 'refresh')

        when:
        store.put('key1', auth)

        then:
        store.get('key1') == auth
        store.get('unknown') == null
    }

    def 'should remove auth'() {
        given:
        def store = new HxMapTokenStore()
        def auth = new DefaultHxAuth('my.jwt.token', 'refresh')
        store.put('key1', auth)

        when:
        def removed = store.remove('key1')

        then:
        removed == auth
        store.get('key1') == null
        store.remove('key1') == null
    }

    def 'putIfAbsent should store and return auth when key is absent'() {
        given:
        def store = new HxMapTokenStore()
        def auth = new DefaultHxAuth('my.jwt.token', 'refresh')

        when:
        def result = store.putIfAbsent('key1', auth)

        then:
        result == auth
        store.get('key1') == auth
    }

    def 'putIfAbsent should return existing auth when key is present'() {
        given:
        def store = new HxMapTokenStore()
        def existing = new DefaultHxAuth('existing.jwt.token', 'refresh1')
        def newAuth = new DefaultHxAuth('new.jwt.token', 'refresh2')
        store.put('key1', existing)

        when:
        def result = store.putIfAbsent('key1', newAuth)

        then:
        result == existing
        store.get('key1') == existing
    }

}
