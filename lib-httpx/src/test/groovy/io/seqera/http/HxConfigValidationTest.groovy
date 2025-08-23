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
 * Test cases for HxConfig validation logic.
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxConfigValidationTest extends Specification {

    def "should allow JWT token only"() {
        when:
        def config = HxConfig.newBuilder()
                .withBearerToken("jwt.token.here")
                .build()
        
        then:
        config.jwtToken == "jwt.token.here"
        config.basicAuthToken == null
    }

    def "should allow Basic auth only"() {
        when:
        def config = HxConfig.newBuilder()
                .withBasicAuth("user", "pass")
                .build()
        
        then:
        config.basicAuthToken == "user:pass"
        config.jwtToken == null
    }

    def "should allow Basic auth token directly"() {
        when:
        def config = HxConfig.newBuilder()
                .withBasicAuth("user:pass:with:colons")
                .build()
        
        then:
        config.basicAuthToken == "user:pass:with:colons"
        config.jwtToken == null
    }

    def "should reject both JWT and Basic auth configured"() {
        when:
        HxConfig.newBuilder()
                .withBearerToken("jwt.token.here")
                .withBasicAuth("user", "pass")
                .build()
        
        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Cannot configure both JWT token and Basic authentication")
    }

    def "should reject both JWT and Basic auth token configured"() {
        when:
        HxConfig.newBuilder()
                .withBearerToken("jwt.token.here")
                .withBasicAuth("user:pass")
                .build()
        
        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Cannot configure both JWT token and Basic authentication")
    }

    def "should allow no authentication"() {
        when:
        def config = HxConfig.newBuilder()
                .withMaxAttempts(3)
                .build()
        
        then:
        config.jwtToken == null
        config.basicAuthToken == null
        config.maxAttempts == 3
    }

    def "should allow null JWT token with Basic auth"() {
        when:
        def config = HxConfig.newBuilder()
                .withBearerToken(null)
                .withBasicAuth("user", "pass")
                .build()
        
        then:
        config.jwtToken == null
        config.basicAuthToken == "user:pass"
    }
}
