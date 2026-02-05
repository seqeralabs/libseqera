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
 */
package io.seqera.lib.hash

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for Sha256Hasher.
 *
 * @author Paolo Di Tommaso
 */
class Sha256HasherTest extends Specification {

    // ==========================================
    // Consistency tests
    // ==========================================

    def 'should produce consistent hash for same input'() {
        given:
        def hasher1 = new Sha256Hasher()
        def hasher2 = new Sha256Hasher()

        when:
        hasher1.putString('hello').putInt(42).putBoolean(true)
        hasher2.putString('hello').putInt(42).putBoolean(true)

        then:
        hasher1.toLong() == hasher2.toLong()
    }

    def 'should be deterministic across multiple calls'() {
        given:
        def results = (1..100).collect {
            new Sha256Hasher().putString('test').putInt(123).toLong()
        }

        expect:
        results.unique().size() == 1
    }

    // ==========================================
    // Differentiation tests
    // ==========================================

    def 'should produce different hash for different strings'() {
        given:
        def hasher1 = new Sha256Hasher()
        def hasher2 = new Sha256Hasher()

        when:
        hasher1.putString('hello')
        hasher2.putString('world')

        then:
        hasher1.toLong() != hasher2.toLong()
    }

    def 'should be order sensitive for strings'() {
        given:
        def hasher1 = new Sha256Hasher()
        def hasher2 = new Sha256Hasher()

        when:
        hasher1.putString('a').putString('b')
        hasher2.putString('b').putString('a')

        then:
        hasher1.toLong() != hasher2.toLong()
    }

    // ==========================================
    // Null and empty handling
    // ==========================================

    def 'should handle null string as no-op'() {
        given:
        def hasher1 = new Sha256Hasher()
        def hasher2 = new Sha256Hasher()

        when:
        hasher1.putString(null).putString('test')
        hasher2.putString('test')

        then:
        hasher1.toLong() == hasher2.toLong()
    }

    def 'should produce non-zero hash for empty input'() {
        expect:
        new Sha256Hasher().toLong() != 0
    }

    // ==========================================
    // Separator tests
    // ==========================================

    def 'should distinguish separator placement'() {
        given:
        def hasher1 = new Sha256Hasher()
        def hasher2 = new Sha256Hasher()

        when:
        hasher1.putString('ab').putSeparator().putString('cd')
        hasher2.putString('abc').putSeparator().putString('d')

        then:
        hasher1.toLong() != hasher2.toLong()
    }

    // ==========================================
    // Boolean and integer tests
    // ==========================================

    def 'should handle boolean values'() {
        given:
        def hasherTrue = new Sha256Hasher()
        def hasherFalse = new Sha256Hasher()

        when:
        hasherTrue.putBoolean(true)
        hasherFalse.putBoolean(false)

        then:
        hasherTrue.toLong() != hasherFalse.toLong()
    }

    @Unroll
    def 'should handle integer value #value'() {
        given:
        def hasher = new Sha256Hasher()

        when:
        hasher.putInt(value)

        then:
        hasher.toLong() != 0

        where:
        value << [0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE]
    }

    // ==========================================
    // Boundary and large input tests
    // ==========================================

    @Unroll
    def 'should handle string of length #length correctly'() {
        given:
        def hasher1 = new Sha256Hasher()
        def hasher2 = new Sha256Hasher()
        def str = 'a' * length

        when:
        hasher1.putString(str)
        hasher2.putString(str)

        then:
        hasher1.toLong() == hasher2.toLong()

        where:
        length << [0, 1, 7, 8, 9, 15, 16, 17, 63, 64, 65]
    }

    def 'should handle large string input'() {
        given:
        def hasher = new Sha256Hasher()
        def largeString = 'x' * 10000

        when:
        hasher.putString(largeString)
        def hash = hasher.toLong()

        then:
        hash != 0
    }

    // ==========================================
    // Collision resistance tests
    // ==========================================

    def 'should have low collision rate for sequential strings'() {
        given:
        def hashes = (1..1000).collect { i ->
            new Sha256Hasher().putString("item-$i").toLong()
        }

        expect:
        hashes.unique().size() == 1000
    }

    // ==========================================
    // Avalanche effect tests
    // ==========================================

    def 'should exhibit avalanche effect for strings'() {
        given:
        def hash1 = new Sha256Hasher().putString('test').toLong()
        def hash2 = new Sha256Hasher().putString('Test').toLong()

        when:
        def xor = hash1 ^ hash2
        def bitsChanged = Long.bitCount(xor)

        then: 'at least 25% of bits should differ (16 out of 64)'
        bitsChanged >= 16
    }

    // ==========================================
    // Method chaining tests
    // ==========================================

    def 'should support fluent method chaining'() {
        given:
        def hasher = new Sha256Hasher()

        when:
        def result = hasher
            .putString('a')
            .putInt(1)
            .putBoolean(true)
            .putSeparator()
            .putString('b')
            .toLong()

        then:
        result != 0
    }
}
