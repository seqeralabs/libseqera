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

package io.seqera.http

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.Authenticator
import java.net.CookiePolicy
import java.net.ProxySelector
import java.net.http.HttpClient
import java.time.Duration
import java.util.function.Predicate

import io.seqera.http.auth.AuthenticationCallback
import io.seqera.http.auth.AuthenticationScheme
import spock.lang.Specification
/**
 * Verifies that an HxConfig survives a round-trip through HxConfig.newBuilder(HxConfig) and
 * HxClient.Builder.config(HxConfig) without losing any setting.
 *
 * <p>The checks are reflective on purpose: the copy in HxConfig.Builder.copyFrom() mirrors the
 * assignments in HxConfig.Builder.build(), and a field missing from either list is exactly the
 * defect this suite exists to catch (issue #113, where retryCondition, tokenRefreshTimeout and
 * refreshCookiePolicy were dropped). A field added to HxConfig later is not set by the fixtures
 * below, so it fails the coverage test until someone covers it.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HxConfigRoundTripTest extends Specification {

    static final Predicate RETRY_COND = { Throwable t -> false } as Predicate

    static final AuthenticationCallback AUTH_CALLBACK =
            { AuthenticationScheme scheme, String realm -> 'creds' } as AuthenticationCallback

    static final ProxySelector PROXY_SELECTOR = ProxySelector.of(new InetSocketAddress('localhost', 3128))

    static final Authenticator PROXY_AUTHENTICATOR = new Authenticator() { }

    /**
     * A builder with every HxConfig setting at a non-default value, except the authentication
     * ones - HxConfig.Builder.build() rejects a bearer and a basic auth token together, and
     * HxTokenManager rejects refresh components without a bearer token, so the two fixtures
     * below split those fields between them.
     */
    private static HxConfig.Builder allSettings() {
        HxConfig.newBuilder()
                .delay(Duration.ofSeconds(1))
                .maxDelay(Duration.ofSeconds(90))
                .maxAttempts(7)
                .jitter(0.5d)
                .multiplier(3.0d)
                .retryCondition(RETRY_COND)
                .retryStatusCodes(Set.of(418))
                .tokenRefreshTimeout(Duration.ofSeconds(45))
                .wwwAuthentication(true)
                .wwwAuthenticationCallback(AUTH_CALLBACK)
                .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
                .proxySelector(PROXY_SELECTOR)
                .proxyAuthenticator(PROXY_AUTHENTICATOR)
    }

    private static HxConfig jwtConfig() {
        allSettings()
                .bearerToken('jwt-token')
                .refreshToken('refresh-token')
                .refreshTokenUrl('https://example.com/oauth/token')
                .build()
    }

    private static HxConfig basicConfig() {
        allSettings().basicAuth('the-user:the-password').build()
    }

    /** The declared instance fields of HxConfig, readable */
    private static List<Field> configFields() {
        HxConfig.getDeclaredFields()
                .findAll { !it.synthetic && !Modifier.isStatic(it.modifiers) }
                .each { it.setAccessible(true) }
    }

    private static List<String> diff(HxConfig expected, HxConfig actual) {
        configFields()
                .findAll { Field f -> f.get(expected) != f.get(actual) }
                .collect { Field f -> "${f.name}: expected '${f.get(expected)}' but was '${f.get(actual)}'".toString() }
    }

    def 'every HxConfig field should be covered by a fixture with a non-default value'() {
        given: 'the fixtures used by the round-trip tests below'
        def defaults = HxConfig.newBuilder().build()
        def jwt = jwtConfig()
        def basic = basicConfig()

        when: 'looking for fields left at their default value by both fixtures'
        def uncovered = configFields()
                .findAll { Field f -> f.get(jwt) == f.get(defaults) && f.get(basic) == f.get(defaults) }
                .collect { Field f -> f.name }

        then: 'a new HxConfig field must be added to allSettings() to be round-trip tested'
        uncovered == []
    }

    def 'should copy every setting into a new builder'() {
        given:
        def original = jwtConfig()

        when:
        def copy = HxConfig.newBuilder(original).build()

        then:
        diff(original, copy) == []
    }

    def 'should override a copied setting with a later builder call'() {
        given:
        def original = jwtConfig()

        when:
        def copy = HxConfig.newBuilder(original)
                .maxAttempts(11)
                .build()

        then:
        copy.maxAttempts == 11
        and: 'everything else is unchanged'
        diff(original, copy) == ["maxAttempts: expected '7' but was '11'"]
    }

    def 'should reject a null config'() {
        when:
        HxConfig.newBuilder(null)
        then:
        thrown(NullPointerException)
    }

    def 'should copy every setting into the client config - #fixture, explicit httpClient: #explicitClient'() {
        given:
        def original = fixture == 'jwt' ? jwtConfig() : basicConfig()

        when:
        def builder = HxClient.newBuilder().config(original)
        if( explicitClient )
            builder.httpClient(HttpClient.newBuilder().build())
        def actual = builder.build().config

        then:
        diff(original, actual) == []

        where:
        [fixture, explicitClient] << [['jwt', 'basic'], [true, false]].combinations()
    }

    def 'should carry the fields that config() used to drop'() {
        given: 'the three settings reported in issue #113'
        def config = HxConfig.newBuilder()
                .retryCondition(RETRY_COND)
                .tokenRefreshTimeout(Duration.ofSeconds(45))
                .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
                .build()

        when:
        def actual = HxClient.newBuilder().config(config).build().config

        then:
        actual.retryCondition.is(RETRY_COND)
        actual.tokenRefreshTimeout == Duration.ofSeconds(45)
        actual.refreshCookiePolicy == CookiePolicy.ACCEPT_ALL
    }

    def 'should discard a proxy set before config() when the config carries none'() {
        given: 'a config with no proxy settings'
        def config = HxConfig.newBuilder().build()

        when: 'the proxy is set before the config is supplied'
        def before = HxClient.newBuilder()
                .proxy(PROXY_SELECTOR)
                .config(config)
                .build()
                .config

        then: 'config() replaced it - proxy(...) is not exempt from being discarded'
        before.proxySelector == null

        when: 'the proxy is set after the config is supplied'
        def after = HxClient.newBuilder()
                .config(config)
                .proxy(PROXY_SELECTOR)
                .build()
                .config

        then:
        after.proxySelector.is(PROXY_SELECTOR)
    }

    def 'should discard builder settings applied before config()'() {
        when: 'maxAttempts is set before the config is supplied'
        def actual = HxClient.newBuilder()
                .maxAttempts(11)
                .config(jwtConfig())
                .build()
                .config

        then: 'the config wins - config() replaces the builder state'
        actual.maxAttempts == 7

        when: 'maxAttempts is set after the config is supplied'
        actual = HxClient.newBuilder()
                .config(jwtConfig())
                .maxAttempts(11)
                .build()
                .config

        then: 'the later call wins'
        actual.maxAttempts == 11
    }
}
