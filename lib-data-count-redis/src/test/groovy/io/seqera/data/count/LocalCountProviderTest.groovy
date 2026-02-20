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

package io.seqera.data.count

import spock.lang.Specification

import io.seqera.data.count.impl.LocalCountProvider
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class LocalCountProviderTest extends Specification {

    def 'should increment counter' () {
        given:
        def provider = new LocalCountProvider()

        expect:
        provider.increment('foo', 1) == 1
        and:
        provider.increment('foo', 1) == 2
        and:
        provider.increment('foo', 5) == 7
        and:
        provider.get('foo') == 7
    }

    def 'should decrement counter' () {
        given:
        def provider = new LocalCountProvider()

        expect:
        provider.decrement('foo', 1) == -1
        and:
        provider.decrement('foo', 1) == -2
        and:
        provider.decrement('foo', 3) == -5
        and:
        provider.get('foo') == -5
    }

    def 'should get zero for missing key' () {
        given:
        def provider = new LocalCountProvider()

        expect:
        provider.get('missing') == 0
    }

    def 'should handle multiple keys' () {
        given:
        def provider = new LocalCountProvider()

        when:
        provider.increment('foo', 10)
        provider.increment('bar', 20)

        then:
        provider.get('foo') == 10
        and:
        provider.get('bar') == 20
    }

    def 'should clear counter' () {
        given:
        def provider = new LocalCountProvider()

        when:
        provider.increment('foo', 10)
        and:
        provider.clear('foo')

        then:
        provider.get('foo') == 0
    }

    def 'should handle increment and decrement together' () {
        given:
        def provider = new LocalCountProvider()

        expect:
        provider.increment('foo', 10) == 10
        and:
        provider.decrement('foo', 3) == 7
        and:
        provider.increment('foo', 5) == 12
        and:
        provider.decrement('foo', 15) == -3
        and:
        provider.get('foo') == -3
    }
}
