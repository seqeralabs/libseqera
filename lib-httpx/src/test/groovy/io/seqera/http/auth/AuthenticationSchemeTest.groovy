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

package io.seqera.http.auth


import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for AuthenticationScheme enum.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class AuthenticationSchemeTest extends Specification {

    def "should have correct scheme names"() {
        expect:
        AuthenticationScheme.BASIC.schemeName == "Basic"
        AuthenticationScheme.BEARER.schemeName == "Bearer"
    }

    def "should return scheme name in toString"() {
        expect:
        AuthenticationScheme.BASIC.toString() == "Basic"
        AuthenticationScheme.BEARER.toString() == "Bearer"
    }

    @Unroll
    def "should parse scheme from string case insensitive: #input -> #expected"() {
        expect:
        AuthenticationScheme.fromString(input) == expected

        where:
        input     | expected
        "Basic"   | AuthenticationScheme.BASIC
        "basic"   | AuthenticationScheme.BASIC
        "BASIC"   | AuthenticationScheme.BASIC
        "Bearer"  | AuthenticationScheme.BEARER
        "bearer"  | AuthenticationScheme.BEARER
        "BEARER"  | AuthenticationScheme.BEARER
    }

    @Unroll
    def "should return null for invalid scheme names: #input"() {
        expect:
        AuthenticationScheme.fromString(input) == null

        where:
        input << [null, "", "  ", "Digest", "Unknown", "OAuth", "123"]
    }

    def "should handle whitespace in scheme names"() {
        expect:
        AuthenticationScheme.fromString(" Basic ") == AuthenticationScheme.BASIC
        AuthenticationScheme.fromString(" Bearer ") == AuthenticationScheme.BEARER
    }

    def "should provide getSchemeName method"() {
        expect:
        AuthenticationScheme.BASIC.getSchemeName() == "Basic"
        AuthenticationScheme.BEARER.getSchemeName() == "Bearer"
    }

    def "should have exactly two supported schemes"() {
        expect:
        AuthenticationScheme.values().length == 2
        AuthenticationScheme.values() as Set == [AuthenticationScheme.BASIC, AuthenticationScheme.BEARER] as Set
    }
}
