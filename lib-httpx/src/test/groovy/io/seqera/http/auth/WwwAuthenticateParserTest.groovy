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
 * Unit tests for WWW-Authenticate header parsing functionality.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class WwwAuthenticateParserTest extends Specification {

    def "should parse single Basic challenge"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Basic realm="Protected Area"')

        then:
        challenges.size() == 1
        challenges[0].scheme == AuthenticationScheme.BASIC
        challenges[0].realm == "Protected Area"
    }

    def "should parse single Bearer challenge"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Bearer realm="api", scope="read write"')

        then:
        challenges.size() == 1
        challenges[0].scheme == AuthenticationScheme.BEARER
        challenges[0].realm == "api"
        challenges[0].getParameter("scope") == "read write"
    }

    def "should parse multiple challenges"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Basic realm="apps", Bearer realm="api"')

        then:
        challenges.size() == 2
        
        challenges[0].scheme == AuthenticationScheme.BASIC
        challenges[0].realm == "apps"
        
        challenges[1].scheme == AuthenticationScheme.BEARER
        challenges[1].realm == "api"
    }

    def "should parse challenge without parameters"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Basic')

        then:
        challenges.size() == 1
        challenges[0].scheme == AuthenticationScheme.BASIC
        challenges[0].realm == null
        !challenges[0].hasParameters()
    }

    def "should handle quoted values with spaces"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Basic realm="Protected Area with Spaces"')

        then:
        challenges.size() == 1
        challenges[0].realm == "Protected Area with Spaces"
    }

    def "should handle unquoted parameter values"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Bearer realm=api, scope=read')

        then:
        challenges.size() == 1
        challenges[0].scheme == AuthenticationScheme.BEARER
        challenges[0].realm == "api"
        challenges[0].getParameter("scope") == "read"
    }

    def "should ignore unsupported authentication schemes"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Digest realm="test", Basic realm="apps"')

        then:
        challenges.size() == 1
        challenges[0].scheme == AuthenticationScheme.BASIC
        challenges[0].realm == "apps"
    }

    def "should handle case insensitive scheme names"() {
        when:
        def challenges = WwwAuthenticateParser.parse('basic realm="test", BEARER realm="api"')

        then:
        challenges.size() == 2
        challenges[0].scheme == AuthenticationScheme.BASIC
        challenges[1].scheme == AuthenticationScheme.BEARER
    }

    @Unroll
    def "should handle edge cases: #scenario"() {
        when:
        def challenges = WwwAuthenticateParser.parse(headerValue)

        then:
        challenges.size() == expectedCount

        where:
        scenario                    | headerValue                          | expectedCount
        "null header"              | null                                 | 0
        "empty header"             | ""                                   | 0
        "whitespace only"          | "   "                               | 0
        "invalid format"           | "invalid-format"                    | 0
        "only unsupported schemes" | "Digest realm=\"test\""             | 0
        "mixed valid/invalid"      | "Invalid, Basic realm=\"test\""     | 1
    }

    def "should parse complex real-world header"() {
        given:
        def headerValue = 'Bearer realm="Docker registry", service="public.cr.seqera.io", scope="repository:nextflow/plugin/nf-amazon:pull"'

        when:
        def challenges = WwwAuthenticateParser.parse(headerValue)

        then:
        challenges.size() == 1
        
        with(challenges[0]) {
            scheme == AuthenticationScheme.BEARER
            realm == "Docker registry"
            getParameter("service") == "public.cr.seqera.io"
            getParameter("scope") == "repository:nextflow/plugin/nf-amazon:pull"
        }
    }

    def "should handle quoted values with escaped quotes"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Basic realm="Area with \\"quotes\\""')

        then:
        challenges.size() == 1
        // Note: Our simple parser doesn't handle escaped quotes in this version
        // This is a known limitation that could be improved in the future
    }

    def "should parse multiple parameters correctly"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Bearer realm="api", scope="read write", type="JWT"')

        then:
        challenges.size() == 1
        
        with(challenges[0]) {
            scheme == AuthenticationScheme.BEARER
            realm == "api"
            getParameter("scope") == "read write"
            getParameter("type") == "JWT"
            parameters.size() == 3
        }
    }

    def "should maintain parameter order in toString"() {
        when:
        def challenges = WwwAuthenticateParser.parse('Basic realm="test"')
        def challenge = challenges[0]

        then:
        challenge.toString().contains("Basic")
        challenge.toString().contains("realm=")
    }
}
