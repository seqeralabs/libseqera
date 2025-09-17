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

import java.net.CookiePolicy
import java.time.Duration

import io.seqera.util.retry.Retryable
import spock.lang.Specification
/**
 * Unit tests for HxConfig
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxConfigTest extends Specification {

    def 'should create config with defaults'() {
        when:
        def config = HxConfig.newBuilder().build()

        then:
        config.delay == Duration.ofMillis(500)
        config.maxDelay == Duration.ofSeconds(30)
        config.maxAttempts == 5
        config.jitter == 0.25d
        config.multiplier == 2.0d
        config.retryStatusCodes == [429, 500, 502, 503, 504] as Set
        config.tokenRefreshTimeout == Duration.ofSeconds(30)
        config.jwtToken == null
        config.refreshToken == null
        config.refreshTokenUrl == null
        config.refreshCookiePolicy == null
        config.retryCondition != null
        config.retryCondition.test(new IOException('test')) == true
        config.retryCondition.test(new RuntimeException('test')) == false
    }

    def 'should create config with custom values'() {
        when:
        def config = HxConfig.newBuilder()
                .withDelay(Duration.ofSeconds(1))
                .withMaxDelay(Duration.ofMinutes(2))
                .withMaxAttempts(3)
                .withJitter(0.5)
                .withMultiplier(1.5)
                .withRetryStatusCodes([429, 503] as Set)
                .withBearerToken('jwt-token')
                .withRefreshToken('refresh-token')
                .withRefreshTokenUrl('https://example.com/oauth/token')
                .withTokenRefreshTimeout(Duration.ofSeconds(60))
                .build()

        then:
        config.delay == Duration.ofSeconds(1)
        config.maxDelay == Duration.ofMinutes(2)
        config.maxAttempts == 3
        config.jitter == 0.5
        config.multiplier == 1.5
        config.retryStatusCodes == [429, 503] as Set
        config.jwtToken == 'jwt-token'
        config.refreshToken == 'refresh-token'
        config.refreshTokenUrl == 'https://example.com/oauth/token'
        config.tokenRefreshTimeout == Duration.ofSeconds(60)
    }

    def 'should use default retry condition'() {
        when:
        def config = HxConfig.newBuilder().build()

        then:
        config.retryCondition != null
        
        // Should retry on IOException and its subclasses
        config.retryCondition.test(new IOException('network error')) == true
        config.retryCondition.test(new java.net.ConnectException('connection refused')) == true
        config.retryCondition.test(new java.net.SocketTimeoutException('timeout')) == true
        config.retryCondition.test(new java.io.FileNotFoundException('file not found')) == true
        
        // Should NOT retry on other exceptions
        config.retryCondition.test(new RuntimeException('runtime error')) == false
        config.retryCondition.test(new IllegalArgumentException('invalid arg')) == false
        config.retryCondition.test(new NullPointerException('null pointer')) == false
        config.retryCondition.test(new Exception('generic exception')) == false
    }

    def 'should create config with custom retry condition'() {
        given:
        def customCondition = { throwable -> throwable instanceof IllegalArgumentException }

        when:
        def config = HxConfig.newBuilder()
                .withRetryCondition(customCondition)
                .build()

        then:
        config.retryCondition != null
        config.retryCondition.test(new IllegalArgumentException('test')) == true
        config.retryCondition.test(new IOException('test')) == false
        config.retryCondition.test(new RuntimeException('test')) == false
    }

    def 'should implement Retryable.Config interface'() {
        given:
        def config = HxConfig.newBuilder()
                .withDelay(Duration.ofSeconds(2))
                .withMaxDelay(Duration.ofMinutes(1))
                .withMaxAttempts(10)
                .withJitter(0.1)
                .withMultiplier(3.0)
                .build()

        expect:
        config.getDelay() == Duration.ofSeconds(2)
        config.getMaxDelay() == Duration.ofMinutes(1)
        config.getMaxAttempts() == 10
        config.getJitter() == 0.1
        config.getMultiplier() == 3.0
    }

    def 'should build from Retryable.Config'() {
        given:
        def retryableConfig = Retryable.ofDefaults().config()
        
        when:
        def httpConfig = HxConfig.newBuilder()
                .withRetryConfig(retryableConfig)
                .withBearerToken('test-token')
                .build()

        then:
        httpConfig.getDelay() == retryableConfig.getDelay()
        httpConfig.getMaxDelay() == retryableConfig.getMaxDelay()
        httpConfig.getMaxAttempts() == retryableConfig.getMaxAttempts()
        httpConfig.getJitter() == retryableConfig.getJitter()
        httpConfig.getMultiplier() == retryableConfig.getMultiplier()
        httpConfig.jwtToken == 'test-token'
    }

    def 'should handle null Retryable.Config gracefully'() {
        when:
        def httpConfig = HxConfig.newBuilder()
                .withRetryConfig(null)
                .withBearerToken('test-token')
                .build()

        then:
        httpConfig.getDelay() == Duration.ofMillis(500)
        httpConfig.getMaxDelay() == Duration.ofSeconds(30)
        httpConfig.getMaxAttempts() == 5
        httpConfig.getJitter() == 0.25d
        httpConfig.getMultiplier() == 2.0
        httpConfig.jwtToken == 'test-token'
    }
    
    def 'should create config with refreshCookiePolicy'() {
        when:
        def config = HxConfig.newBuilder()
                .withRefreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
                .build()
        
        then:
        config.refreshCookiePolicy == CookiePolicy.ACCEPT_ALL
    }
    
    def 'should create config with null refreshCookiePolicy by default'() {
        when:
        def config = HxConfig.newBuilder().build()
        
        then:
        config.refreshCookiePolicy == null
    }
    
    def 'should support all cookie policy types'() {
        expect:
        def config1 = HxConfig.newBuilder()
                .withRefreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
                .build()
        config1.refreshCookiePolicy == CookiePolicy.ACCEPT_ALL
        
        def config2 = HxConfig.newBuilder()
                .withRefreshCookiePolicy(CookiePolicy.ACCEPT_NONE)
                .build()
        config2.refreshCookiePolicy == CookiePolicy.ACCEPT_NONE
        
        def config3 = HxConfig.newBuilder()
                .withRefreshCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)
                .build()
        config3.refreshCookiePolicy == CookiePolicy.ACCEPT_ORIGINAL_SERVER
    }
}
