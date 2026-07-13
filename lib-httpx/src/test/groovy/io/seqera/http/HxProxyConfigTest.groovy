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
 * Test cases for {@link HxProxyConfig} proxy selection and proxy authentication behaviour.
 */
class HxProxyConfigTest extends Specification {

    private static PasswordAuthentication invokeAuth(Authenticator authenticator, Authenticator.RequestorType type, String host, int port) {
        // drive the protected Authenticator API the same way the JDK HTTP client does
        return authenticator.requestPasswordAuthenticationInstance(
                host, null, port, 'http', 'proxy auth', 'basic', new URL('http://example.com'), type )
    }

    def 'builder should assemble a proxy config from explicit values'() {
        when:
        def config = HxProxyConfig.newBuilder()
                .httpsProxy('proxy.example.com', 8080, 'foo', 'bar')
                .noProxy(['internal.example.com'])
                .build()

        then:
        config.httpsProxy.host == 'proxy.example.com'
        config.httpsProxy.port == 8080
        config.httpsProxy.username == 'foo'
        config.httpsProxy.password == 'bar'
        config.httpProxy == null
        config.hasCredentials()
        and: 'the selector honours the explicit NO_PROXY entry'
        config.isBypassed('internal.example.com')
        !config.isBypassed('api.example.com')
        and:
        config.toAuthenticator() != null
    }

    def 'builder without credentials yields no authenticator'() {
        when:
        def config = HxProxyConfig.newBuilder()
                .httpProxy('proxy.local', 3128, null, null)
                .build()

        then:
        config.httpProxy.host == 'proxy.local'
        config.httpProxy.port == 3128
        !config.hasCredentials()
        config.toAuthenticator() == null
    }

    def 'should select the proxy matching the target scheme'() {
        given:
        def config = HxProxyConfig.newBuilder()
                .httpProxy('http-proxy', 3128, null, null)
                .httpsProxy('https-proxy', 3129, null, null)
                .build()
        def selector = config.toProxySelector()

        when:
        def httpResult = selector.select(URI.create('http://api.example.com/foo'))
        def httpsResult = selector.select(URI.create('https://api.example.com/foo'))

        then:
        (httpResult[0].address() as InetSocketAddress).hostString == 'http-proxy'
        (httpResult[0].address() as InetSocketAddress).port == 3128
        (httpsResult[0].address() as InetSocketAddress).hostString == 'https-proxy'
        (httpsResult[0].address() as InetSocketAddress).port == 3129
    }

    def 'should connect directly when no proxy matches the target scheme'() {
        given:
        def config = HxProxyConfig.newBuilder().httpsProxy('proxy', 3128, null, null).build()

        expect:
        config.toProxySelector().select(URI.create('http://api.example.com/foo')) == [Proxy.NO_PROXY]
    }

    def 'should bypass proxy for NO_PROXY entries'() {
        given:
        def config = HxProxyConfig.newBuilder()
                .httpProxy('proxy', 3128, null, null)
                .httpsProxy('proxy', 3128, null, null)
                .noProxy(['internal.example.com', '.corp.example.org', '*.svc.cluster.local'])
                .build()

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
        def config = HxProxyConfig.newBuilder().httpsProxy('proxy', 3128, null, null).noProxy(['*']).build()

        expect:
        config.toProxySelector().select(URI.create('https://api.example.com')) == [Proxy.NO_PROXY]
    }

    def 'should always bypass loopback targets'() {
        given:
        def config = HxProxyConfig.newBuilder().httpsProxy('proxy', 3128, null, null).build()

        expect:
        config.isBypassed('localhost')
        config.isBypassed('127.0.0.1')
        !config.isBypassed('api.example.com')
    }

    def 'should provide credentials only for matching proxy requests'() {
        given:
        def config = HxProxyConfig.newBuilder().httpsProxy('proxy.example.com', 8080, 'foo', 'bar').build()
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
}
