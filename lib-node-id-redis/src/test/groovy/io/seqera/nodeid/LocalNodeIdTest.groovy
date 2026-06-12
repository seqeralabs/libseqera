/*
 * Copyright 2026, Seqera Labs
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

package io.seqera.nodeid

import spock.lang.Specification

class LocalNodeIdTest extends Specification {

    def 'should report the configured capacity'() {
        expect:
        new LocalNodeId(1024).capacity() == 1024
    }

    def 'should assign rotating ordinals from an in-memory counter'() {
        when: 'several instances are created within the same process'
        def values = (1..4).collect { new LocalNodeId(3).value() }

        then: 'each ordinal is the previous one incremented, modulo capacity'
        def base = values[0]
        values == [base, (base + 1) % 3, (base + 2) % 3, (base + 3) % 3]
        and: 'every ordinal is within range'
        values.every { it >= 0 && it < 3 }
    }
}
