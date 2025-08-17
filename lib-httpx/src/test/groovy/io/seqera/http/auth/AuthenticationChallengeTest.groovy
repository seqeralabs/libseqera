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

/**
 * Unit tests for AuthenticationChallenge class.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class AuthenticationChallengeTest extends Specification {

    def "should create challenge with scheme only"() {
        when:
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BASIC)

        then:
        challenge.scheme == AuthenticationScheme.BASIC
        challenge.realm == null
        !challenge.hasParameters()
        challenge.parameters.isEmpty()
    }

    def "should create challenge with scheme and parameters"() {
        given:
        def params = [realm: "Protected Area", scope: "read write"]

        when:
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BEARER, params)

        then:
        challenge.scheme == AuthenticationScheme.BEARER
        challenge.realm == "Protected Area"
        challenge.getParameter("scope") == "read write"
        challenge.hasParameters()
        challenge.parameters.size() == 2
    }

    def "should handle null parameters"() {
        when:
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BASIC, null)

        then:
        challenge.scheme == AuthenticationScheme.BASIC
        challenge.realm == null
        !challenge.hasParameters()
        challenge.parameters.isEmpty()
    }

    def "should throw exception for null scheme"() {
        when:
        new AuthenticationChallenge(null)

        then:
        thrown(NullPointerException)
    }

    def "should return immutable parameters map"() {
        given:
        def params = [realm: "test"]
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BASIC, params)

        when:
        challenge.parameters.put("new", "value")

        then:
        thrown(UnsupportedOperationException)
    }

    def "should not affect original parameters map"() {
        given:
        def params = [realm: "test"]
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BASIC, params)

        when:
        params.put("modified", "value")

        then:
        !challenge.parameters.containsKey("modified")
        challenge.parameters.size() == 1
    }

    def "should implement equals and hashCode correctly"() {
        given:
        def params1 = [realm: "test"]
        def params2 = [realm: "test"]
        def params3 = [realm: "different"]

        def challenge1 = new AuthenticationChallenge(AuthenticationScheme.BASIC, params1)
        def challenge2 = new AuthenticationChallenge(AuthenticationScheme.BASIC, params2)
        def challenge3 = new AuthenticationChallenge(AuthenticationScheme.BASIC, params3)
        def challenge4 = new AuthenticationChallenge(AuthenticationScheme.BEARER, params1)

        expect:
        challenge1 == challenge2
        challenge1 != challenge3
        challenge1 != challenge4
        challenge1.hashCode() == challenge2.hashCode()
    }

    def "should generate proper toString representation"() {
        when:
        def challenge1 = new AuthenticationChallenge(AuthenticationScheme.BASIC)
        def challenge2 = new AuthenticationChallenge(AuthenticationScheme.BEARER, [realm: "api", scope: "read"])

        then:
        challenge1.toString() == "Basic"
        challenge2.toString().startsWith("Bearer")
        challenge2.toString().contains("realm=api")
        challenge2.toString().contains("scope=read")
    }

    def "should handle parameters with special characters in toString"() {
        given:
        def params = [realm: "Area with spaces", message: "Hello, World!"]
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BASIC, params)

        when:
        def string = challenge.toString()

        then:
        string.contains("Basic")
        string.contains("realm=\"Area with spaces\"")
        string.contains("message=\"Hello, World!\"")
    }

    def "should get specific parameter values"() {
        given:
        def params = [realm: "test", scope: "read write", type: "JWT"]
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BEARER, params)

        expect:
        challenge.getParameter("realm") == "test"
        challenge.getParameter("scope") == "read write"
        challenge.getParameter("type") == "JWT"
        challenge.getParameter("nonexistent") == null
    }

    def "should provide convenience method for realm"() {
        given:
        def params = [realm: "Protected Area", other: "value"]
        def challenge = new AuthenticationChallenge(AuthenticationScheme.BASIC, params)

        expect:
        challenge.realm == "Protected Area"
        challenge.getParameter("realm") == "Protected Area"
    }
}
