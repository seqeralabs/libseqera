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

package io.seqera.service.pairing

import spock.lang.Specification

/**
 * Verify CacheKey produces the same MD5 hashes as DigestFunctions
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CacheKeyTest extends Specification {

    def 'should compute md5 of string'() {
        expect:
        CacheKey.md5('hello') == '5d41402abc4b2a76b9719d911017c592'
        CacheKey.md5('') == 'd41d8cd98f00b204e9800998ecf8427e'
        CacheKey.md5((String) null) == '93b885adfe0da089cdf634904fd59f71'
    }

    def 'should compute md5 of map'() {
        given:
        def attrs = [service: 'tower', towerEndpoint: 'https://api.cloud.seqera.io'] as Map<String, Object>

        when:
        def hash = CacheKey.md5(attrs)

        then:
        hash != null
        hash.length() == 32
    }

    def 'should produce consistent results'() {
        given:
        def attrs = [service: 'tower', towerEndpoint: 'https://api.cloud.seqera.io'] as Map<String, Object>

        expect:
        CacheKey.md5(attrs) == CacheKey.md5(attrs)
    }

    def 'should reject null or empty map'() {
        when:
        CacheKey.md5((Map) null)
        then:
        thrown(IllegalArgumentException)

        when:
        CacheKey.md5([:] as Map<String, Object>)
        then:
        thrown(IllegalArgumentException)
    }
}
