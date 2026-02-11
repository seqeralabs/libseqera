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

package io.seqera.data.range

import spock.lang.Specification

import io.seqera.data.range.impl.LocalRangeProvider
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class LocalRangeProviderTest extends Specification {


    def 'should add and get elements' () {
        given:
        def provider = new LocalRangeProvider()
        and:
        provider.add('foo', 'x', 1)
        provider.add('foo', 'y', 2)
        provider.add('foo', 'z', 3)
        provider.add('bar', 'z', 10)

        expect:
        provider.getRange('foo', 0,1, 10, false)
                == ['x']
        and:
        provider.getRange('foo', 1,1, 10, false)
                == ['x']
        and:
        provider.getRange('foo', 1,2, 10, false)
                == ['x','y']
        and:
        provider.getRange('foo', 1,3, 10, false)
                == ['x','y','z']
        and:
        provider.getRange('foo', 1.1,3, 10, false)
                == ['y','z']
        and:
        provider.getRange('foo', 1,3, 1, false)
                == ['x']

        and:
        provider.getRange('bar', 1,3, 1, false)
                == []
        and:
        provider.getRange('bar', 10,10, 1, false)
                == ['z']

        when:
        provider.add('bar', 'z', 20)
        then:
        provider.getRange('bar', 10,10, 1, false)
                == []
        and:
        provider.getRange('bar', 10,20, 1, false)
                == ['z']
        and:
        provider.getRange('bar', 1,100, 100, false)
                == ['z']

    }

    def 'should remove elements' () {
        given:
        def provider = new LocalRangeProvider()
        and:
        provider.add('foo', 'x', 1)
        provider.add('foo', 'y', 2)
        provider.add('foo', 'z', 3)
        provider.add('bar', 'z', 10)

        expect:
        provider.getRange('foo', 1,1, 1, true) == ['x']
        and:
        provider.getRange('foo', 1,10, 10, true) == ['y','z']
        and:
        provider.getRange('foo', 1,10, 10, true) == []

        and:
        provider.getRange('bar', 1,10, 10, true) == ['z']
        and:
        provider.getRange('bar', 1,10, 10, true) == []
    }
}
