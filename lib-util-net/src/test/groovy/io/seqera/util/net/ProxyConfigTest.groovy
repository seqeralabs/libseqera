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

package io.seqera.util.net

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Tests for the {@link ProxyConfig} egress-proxy resolver.
 *
 * <p>Parsing, no-proxy and proxy-authentication semantics mirror Nextflow's
 * {@code nextflow.util.ProxyConfig} (the source of truth).
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ProxyConfigTest extends Specification {

    static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY)

    static List<Proxy> proxied(String host, int port) {
        return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
    }

    @Unroll
    def 'should parse proxy uri #PROXY_URI' () {
        when:
        def config = ProxyConfig.parse(PROXY_URI)
        then:
        config?.protocol() == PROTOCOL
        config?.host() == HOST
        config?.port() == PORT
        config?.username() == USER
        config?.password() == PASS

        where:
        PROXY_URI                                   | PROTOCOL  | HOST                  | PORT      | USER      | PASS
        null                                        | null      | null                  | null      | null      | null
        ''                                          | null      | null                  | null      | null      | null
        'proxy.example.com'                         | null      | 'proxy.example.com'   | null      | null      | null
        'proxy.example.com:3128'                    | null      | 'proxy.example.com'   | '3128'    | null      | null
        'http://proxy.example.com'                  | 'http'    | 'proxy.example.com'   | null      | null      | null
        'https://proxy.example.com:8080'            | 'https'   | 'proxy.example.com'   | '8080'    | null      | null
        'http://foo:bar@proxy.example.com:8080'     | 'http'    | 'proxy.example.com'   | '8080'    | 'foo'     | 'bar'
        'http://foo:p%40ss@proxy.example.com'       | 'http'    | 'proxy.example.com'   | null      | 'foo'     | 'p@ss'
        'http://foo:p+ss@proxy.example.com'         | 'http'    | 'proxy.example.com'   | null      | 'foo'     | 'p+ss'
        'http://foo:b:ar@proxy.example.com:1234'    | 'http'    | 'proxy.example.com'   | '1234'    | 'foo'     | 'b:ar'
        // a path/query in the URI is ignored
        'http://10.20.30.40:333/some/path'          | 'http'    | '10.20.30.40'         | '333'     | null      | null
        'http://user:pass@10.20.30.40:333/some/path'| 'http'    | '10.20.30.40'         | '333'     | 'user'    | 'pass'
    }

    def 'should resolve a proxy uri applying it to both http and https destinations' () {
        when:
        def config = ProxyConfig.fromUri('http://proxy.example.com:3128')
        def selector = config.toProxySelector()
        then:
        selector.select(new URI('http://quay.io/v2/')) == proxied('proxy.example.com', 3128)
        selector.select(new URI('https://quay.io/v2/')) == proxied('proxy.example.com', 3128)
        and:
        !config.hasCredentials()
        config.toAuthenticator() == null
    }

    @Unroll
    def 'should default the proxy port by protocol for #PROXY_URI' () {
        when:
        def selector = ProxyConfig.fromUri(PROXY_URI).toProxySelector()
        then:
        selector.select(new URI('https://quay.io/v2/')) == proxied('proxy.example.com', PORT)

        where:
        PROXY_URI                   | PORT
        'proxy.example.com'         | 80
        'http://proxy.example.com'  | 80
        'https://proxy.example.com' | 443
    }

    def 'should return no proxy config when the uri is empty' () {
        expect:
        ProxyConfig.fromUri(null) == null
        ProxyConfig.fromUri('') == null
    }

    def 'should give precedence to explicit credentials over uri user-info' () {
        given:
        def config = ProxyConfig.fromUri('http://foo:bar@proxy.example.com:8080', 'this', 'that', null)

        when:
        def result = config.toAuthenticator().requestPasswordAuthenticationInstance(
                'proxy.example.com', null, 8080, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.PROXY)
        then:
        result.userName == 'this'
        result.password == 'that'.toCharArray()
    }

    @Unroll
    def 'should resolve proxy from environment #ENV' () {
        when:
        def config = ProxyConfig.fromEnvironment(ENV)
        def selector = config?.toProxySelector()
        then:
        (selector?.select(new URI('http://foo.com/'))) == HTTP_RESULT
        (selector?.select(new URI('https://foo.com/'))) == HTTPS_RESULT

        where:
        ENV                                                             | HTTP_RESULT               | HTTPS_RESULT
        [:]                                                             | null                      | null
        [HTTPS_PROXY: 'http://proxy1:3128']                             | DIRECT                    | proxied('proxy1', 3128)
        [https_proxy: 'http://proxy1:3128']                             | DIRECT                    | proxied('proxy1', 3128)
        [HTTP_PROXY: 'http://proxy2:8080']                              | proxied('proxy2', 8080)   | DIRECT
        [HTTPS_PROXY: 'http://proxy1:3128', HTTP_PROXY: 'http://x:1']   | proxied('x', 1)           | proxied('proxy1', 3128)
        [HTTPS_PROXY: 'https://proxy1']                                 | DIRECT                    | proxied('proxy1', 443)
    }

    def 'should resolve proxy credentials and no-proxy from environment' () {
        when:
        def config = ProxyConfig.fromEnvironment([HTTPS_PROXY: 'http://foo:bar@proxy1:3128', NO_PROXY: 'a.com'])
        then:
        config.hasCredentials()
        config.toProxySelector().select(new URI('https://a.com/')) == DIRECT
        and:
        def result = config.toAuthenticator().requestPasswordAuthenticationInstance(
                'proxy1', null, 3128, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.PROXY)
        result.userName == 'foo'
        result.password == 'bar'.toCharArray()
    }

    def 'should fall back to ALL_PROXY when protocol-specific vars are absent' () {
        when:
        def config = ProxyConfig.fromEnvironment([ALL_PROXY: 'http://proxy1:3128'])
        def selector = config.toProxySelector()
        then:
        selector.select(new URI('http://foo.com/')) == proxied('proxy1', 3128)
        selector.select(new URI('https://foo.com/')) == proxied('proxy1', 3128)
    }

    @Unroll
    def 'should bypass=#EXPECTED the proxy for host #TARGET with no-proxy #NO_PROXY' () {
        given:
        def selector = ProxyConfig.fromUri('proxy.example.com:3128', null, null, NO_PROXY).toProxySelector()
        expect:
        (selector.select(new URI("https://${TARGET}/")) == DIRECT) == EXPECTED

        where:
        TARGET              | NO_PROXY                      | EXPECTED
        'quay.io'           | null                          | false
        'quay.io'           | ['docker.io']                 | false
        'docker.io'         | ['docker.io']                 | true
        'DOCKER.IO'         | ['docker.io']                 | true
        // a bare host name entry also matches its sub-domains
        'registry.docker.io'| ['docker.io']                 | true
        // a `.` or `*.` suffix entry matches sub-domains only
        'reg.example.com'   | ['.example.com']              | true
        'reg.example.com'   | ['*.example.com']             | true
        'example.com'       | ['.example.com']              | false
        'notexample.com'    | ['.example.com']              | false
        'anything.io'       | ['*']                         | true
        // loopback addresses always bypass the proxy
        'localhost'         | null                          | true
        '127.0.0.1'         | null                          | true
    }

    def 'should create authenticator scoped to the proxy host and requestor type' () {
        given:
        def auth = ProxyConfig.fromUri('http://foo:bar@proxy.example.com:3128').toAuthenticator()

        when: 'the proxy asks for authentication'
        def result = auth.requestPasswordAuthenticationInstance('proxy.example.com', null, 3128, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.PROXY)
        then:
        result.userName == 'foo'
        result.password == 'bar'.toCharArray()

        when: 'a server (not the proxy) asks for authentication'
        result = auth.requestPasswordAuthenticationInstance('proxy.example.com', null, 3128, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.SERVER)
        then:
        result == null

        when: 'a different host asks for proxy authentication'
        result = auth.requestPasswordAuthenticationInstance('other.example.com', null, 3128, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.PROXY)
        then:
        result == null

        when: 'a different port asks for proxy authentication'
        result = auth.requestPasswordAuthenticationInstance('proxy.example.com', null, 8080, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.PROXY)
        then:
        result == null
    }

    def 'should release credentials for the https CONNECT tunnel where the JDK reports protocol http' () {
        given: 'an https-only proxy with credentials, resolved from the environment'
        def auth = ProxyConfig.fromEnvironment([HTTPS_PROXY: 'http://foo:bar@proxy.example.com:3128']).toAuthenticator()

        when: 'the JDK challenges for the CONNECT tunnel reporting the requesting protocol as http'
        def result = auth.requestPasswordAuthenticationInstance('proxy.example.com', null, 3128, 'http', 'auth required', 'basic', null, Authenticator.RequestorType.PROXY)
        then: 'credentials are released because matching is on host+port, not protocol'
        result.userName == 'foo'
        result.password == 'bar'.toCharArray()
    }

    def 'should expose the resolved per-protocol endpoints and no-proxy hosts' () {
        when:
        def cfg = ProxyConfig.fromEnvironment([HTTPS_PROXY: 'http://foo:bar@https-proxy:3129', HTTP_PROXY: 'http://http-proxy:3128', NO_PROXY: 'a.com,b.com'])
        then:
        cfg.httpProxy.host() == 'http-proxy'
        cfg.httpProxy.port() == 3128
        cfg.httpProxy.username() == null
        cfg.httpsProxy.host() == 'https-proxy'
        cfg.httpsProxy.port() == 3129
        cfg.httpsProxy.username() == 'foo'
        cfg.httpsProxy.password() == 'bar'
        cfg.noProxyHosts == ['a.com','b.com']
        and: 'fromUri applies the same endpoint to both protocols'
        with(ProxyConfig.fromUri('proxy:8080')) {
            httpProxy.host() == 'proxy' && httpProxy.port() == 8080
            httpsProxy.host() == 'proxy' && httpsProxy.port() == 8080
        }
    }

    def 'should redact password in string representation' () {
        expect:
        !ProxyConfig.fromUri('http://foo:secret1234@proxy.example.com').toString().contains('secret1234')
    }

    def 'setupFromEnvironment should install per-protocol system properties and return the http/https config' () {
        given:
        def keys = ['http.proxyHost','http.proxyPort','https.proxyHost','https.proxyPort',
                    'ftp.proxyHost','ftp.proxyPort','http.nonProxyHosts','jdk.http.auth.tunneling.disabledSchemes']
        def saved = keys.collectEntries { [(it): System.getProperty(it)] }
        keys.each { System.clearProperty(it) }
        def savedAuth = Authenticator.default

        when:
        def cfg = ProxyConfig.setupFromEnvironment([
                HTTP_PROXY : 'http://alice:secret@http-proxy:3128',
                HTTPS_PROXY: 'http://https-proxy:8080',
                FTP_PROXY  : 'ftp-proxy:2121',
                NO_PROXY   : 'internal.example.com, .corp' ])

        then: 'per-protocol system properties are set (ftp too)'
        System.getProperty('http.proxyHost') == 'http-proxy'
        System.getProperty('http.proxyPort') == '3128'
        System.getProperty('https.proxyHost') == 'https-proxy'
        System.getProperty('https.proxyPort') == '8080'
        System.getProperty('ftp.proxyHost') == 'ftp-proxy'
        System.getProperty('ftp.proxyPort') == '2121'
        and: 'NO_PROXY is installed as pipe-separated http.nonProxyHosts'
        System.getProperty('http.nonProxyHosts') == 'internal.example.com|.corp'
        and: 'credentials present -> tunnelling Basic scheme is enabled'
        System.getProperty('jdk.http.auth.tunneling.disabledSchemes') == ''
        and: 'the returned config carries the http/https proxies and no-proxy for java.net.http clients'
        cfg.hasCredentials()
        cfg.toProxySelector().select(new URI('http://x/')) == proxied('http-proxy', 3128)
        cfg.toProxySelector().select(new URI('https://x/')) == proxied('https-proxy', 8080)
        cfg.toProxySelector().select(new URI('https://internal.example.com/')) == DIRECT

        cleanup:
        keys.each { saved[it] != null ? System.setProperty(it, saved[it]) : System.clearProperty(it) }
        Authenticator.setDefault(savedAuth)
    }

    def 'setupFromEnvironment should return null and touch nothing when no proxy var is set' () {
        expect:
        ProxyConfig.setupFromEnvironment([:]) == null
    }

    def 'should clear the tunnelling disabledSchemes property only when unset' () {
        given:
        def key = 'jdk.http.auth.tunneling.disabledSchemes'
        def previous = System.getProperty(key)

        when: 'the property is not set'
        System.clearProperty(key)
        def changed = ProxyConfig.enableBasicProxyTunneling()
        then:
        changed
        System.getProperty(key) == ''

        when: 'the property is already set by the operator'
        System.setProperty(key, 'Basic')
        changed = ProxyConfig.enableBasicProxyTunneling()
        then: 'the operator value wins and nothing changes'
        !changed
        System.getProperty(key) == 'Basic'

        cleanup:
        if( previous != null ) System.setProperty(key, previous) else System.clearProperty(key)
    }
}
