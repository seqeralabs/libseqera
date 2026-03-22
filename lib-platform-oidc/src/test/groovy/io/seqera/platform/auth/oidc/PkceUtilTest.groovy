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
 */

package io.seqera.platform.auth.oidc

import java.security.MessageDigest

import spock.lang.Specification

class PkceUtilTest extends Specification {

    def 'should generate verifier with correct length'() {
        when:
        def verifier = PkceUtil.generateVerifier()
        then:
        // 32 bytes base64url-encoded = 43 characters (no padding)
        verifier.length() == 43
        // base64url characters only
        verifier ==~ /[A-Za-z0-9_-]+/
    }

    def 'should generate unique verifiers'() {
        when:
        def v1 = PkceUtil.generateVerifier()
        def v2 = PkceUtil.generateVerifier()
        then:
        v1 != v2
    }

    def 'should compute S256 challenge correctly'() {
        given:
        def verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'
        when:
        def challenge = PkceUtil.computeChallenge(verifier)
        then:
        // known SHA-256 of the verifier, base64url-encoded
        def expected = Base64.urlEncoder.withoutPadding().encodeToString(
            MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('US-ASCII'))
        )
        challenge == expected
    }

    def 'should generate state with correct length'() {
        when:
        def state = PkceUtil.generateState()
        then:
        // 16 bytes base64url-encoded = 22 characters (no padding)
        state.length() == 22
        state ==~ /[A-Za-z0-9_-]+/
    }

    def 'should generate unique states'() {
        when:
        def s1 = PkceUtil.generateState()
        def s2 = PkceUtil.generateState()
        then:
        s1 != s2
    }
}
