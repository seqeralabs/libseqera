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

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy

import spock.lang.Specification

/**
 * Test cases for {@link HxProxyConfig} environment parsing, proxy selection and
 * proxy authentication behaviour.
 */
class HxProxyConfigTest extends Specification {

    private static PasswordAuthentication invokeAuth(Authenticator authenticator, Authenticator.RequestorType type, String host, int port) {
        // drive the protected Authenticator API the same way the JDK HTTP client does
        return authenticator.requestPasswordAuthenticationInstance(
                host, null, port, 'http', 'proxy auth', 'basic', new URL('http://example.com'), type )
    }

    def 'should return null when no proxy is configured'() {
        expect:
        HxProxyConfig.fromEnvironment([:], new Properties()) == null
    }

    def 'should parse HTTPS_PROXY with embedded url-encoded credentials'() {
        when:
        def config = HxProxyConfig.fromEnvironment([HTTPS_PROXY: 'http://foo:p%40ss%2Fword@proxy.example.com:8080'], new Properties())

        then:
        config.httpsProxy.host == 'proxy.example.com'
        config.httpsProxy.port == 8080
        config.httpsProxy.username == 'foo'
        config.httpsProxy.password == 'p@ss/word'
        config.hasCredentials()
        and: 'no proxy is configured for plain http targets'
        config.httpProxy == null
    }

    def 'should parse lower-case variables and bare host:port values'() {
        when:
        def config = HxProxyConfig.fromEnvironment([http_proxy: 'proxy.local:3128'], new Properties())

        then:
        config.httpProxy.host == 'proxy.local'
        config.httpProxy.port == 3128
        config.httpProxy.username == null
        !config.hasCredentials()
        config.toAuthenticator() == null
    }

    def 'should prefer upper-case variable over lower-case one'() {
        when:
        def config = HxProxyConfig.fromEnvironment([
                HTTP_PROXY: 'http://upper:80',
                http_proxy: 'http://lower:81' ], new Properties())

        then:
        config.httpProxy.host == 'upper'
    }

    def 'should fall back to ALL_PROXY for both protocols'() {
        when:
        def config = HxProxyConfig.fromEnvironment([ALL_PROXY: 'http://user:pass@allproxy:9090'], new Properties())

        then:
        config.httpProxy.host == 'allproxy'
        config.httpsProxy.host == 'allproxy'
        config.httpsProxy.username == 'user'
        config.httpsProxy.password == 'pass'
    }

    def 'should fall back to java system properties'() {
        given:
        def props = new Properties()
        props.setProperty('https.proxyHost', 'sysprop-proxy')
        props.setProperty('https.proxyPort', '8443')

        when:
        def config = HxProxyConfig.fromEnvironment([:], props)

        then:
        config.httpsProxy.host == 'sysprop-proxy'
        config.httpsProxy.port == 8443
        config.httpProxy == null
        !config.hasCredentials()
    }

    def 'should default the proxy port from the proxy url scheme'() {
        expect:
        HxProxyConfig.parseProxyUrl(value).port == expected

        where:
        value                       | expected
        'http://proxy.example.com'  | 80
        'https://proxy.example.com' | 443
        'proxy.example.com:8080'    | 8080
    }

    def 'should select the proxy matching the target scheme'() {
        given:
        def config = HxProxyConfig.fromEnvironment([
                HTTP_PROXY : 'http://http-proxy:3128',
                HTTPS_PROXY: 'http://https-proxy:3129' ], new Properties())
        def selector = config.toProxySelector()

        when:
        def httpResult = selector.select(URI.create('http://api.example.com/foo'))
        def httpsResult = selector.select(URI.create('https://api.example.com/foo'))

        then:
        httpResult.size() == 1
        (httpResult[0].address() as InetSocketAddress).hostString == 'http-proxy'
        (httpResult[0].address() as InetSocketAddress).port == 3128
        (httpsResult[0].address() as InetSocketAddress).hostString == 'https-proxy'
        (httpsResult[0].address() as InetSocketAddress).port == 3129
    }

    def 'should connect directly when no proxy matches the target scheme'() {
        given:
        def config = HxProxyConfig.fromEnvironment([HTTPS_PROXY: 'http://proxy:3128'], new Properties())

        expect:
        config.toProxySelector().select(URI.create('http://api.example.com/foo')) == [Proxy.NO_PROXY]
    }

    def 'should bypass proxy for NO_PROXY entries'() {
        given:
        def config = HxProxyConfig.fromEnvironment([
                ALL_PROXY: 'http://proxy:3128',
                NO_PROXY : 'internal.example.com, .corp.example.org, *.svc.cluster.local' ], new Properties())

        expect:
        config.isBypassed(host) == bypassed

        where:
        host                          | bypassed
        'internal.example.com'        | true
        'sub.internal.example.com'    | true
        'other.example.com'           | false
        'internal.example.com.evil.io'| false
        'foo.corp.example.org'        | true
        'corp.example.org'            | false
        'db.default.svc.cluster.local'| true
    }

    def 'should bypass everything when NO_PROXY is a wildcard'() {
        given:
        def config = HxProxyConfig.fromEnvironment([ALL_PROXY: 'http://proxy:3128', no_proxy: '*'], new Properties())

        expect:
        config.toProxySelector().select(URI.create('https://api.example.com')) == [Proxy.NO_PROXY]
    }

    def 'should always bypass loopback targets'() {
        given:
        def config = HxProxyConfig.fromEnvironment([ALL_PROXY: 'http://proxy:3128'], new Properties())

        expect:
        config.isBypassed('localhost')
        config.isBypassed('127.0.0.1')
        !config.isBypassed('api.example.com')
    }

    def 'should provide credentials only for matching proxy requests'() {
        given:
        def config = HxProxyConfig.fromEnvironment([HTTPS_PROXY: 'http://foo:bar@proxy.example.com:8080'], new Properties())
        def authenticator = config.toAuthenticator()

        when: 'the proxy itself requests authentication'
        def auth = invokeAuth(authenticator, Authenticator.RequestorType.PROXY, 'proxy.example.com', 8080)

        then:
        auth.userName == 'foo'
        new String(auth.password) == 'bar'

        when: 'an origin server requests authentication'
        auth = invokeAuth(authenticator, Authenticator.RequestorType.SERVER, 'proxy.example.com', 8080)

        then:
        auth == null

        when: 'a different host requests proxy authentication'
        auth = invokeAuth(authenticator, Authenticator.RequestorType.PROXY, 'other-proxy.example.com', 8080)

        then:
        auth == null

        when: 'the right host but a different port requests proxy authentication'
        auth = invokeAuth(authenticator, Authenticator.RequestorType.PROXY, 'proxy.example.com', 9999)

        then:
        auth == null
    }

    def 'should ignore malformed proxy values'() {
        expect:
        HxProxyConfig.parseProxyUrl(null) == null
        HxProxyConfig.parseProxyUrl('') == null
        HxProxyConfig.parseProxyUrl('http://') == null
        HxProxyConfig.parseProxyUrl('::not a url::') == null
    }
}
