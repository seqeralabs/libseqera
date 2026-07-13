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

package io.seqera.http;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds HTTP/HTTPS forward-proxy settings supplied explicitly (host, port and optional
 * credentials per protocol, plus {@code NO_PROXY} entries) and exposes them as a
 * {@link ProxySelector} and a proxy-only {@link Authenticator} suitable for
 * {@link java.net.http.HttpClient}. Build one with {@link #newBuilder()}.
 *
 * <p>The library never reads the environment or system properties on its own; the caller
 * resolves proxy settings however it wishes and passes the values to {@link Builder}.
 *
 * <p><code>NO_PROXY</code> entries are honoured as a list of host names or domain suffixes
 * (optionally prefixed with <code>.</code> or <code>*.</code>); the single entry <code>*</code>
 * disables proxying entirely. Loopback targets (<code>localhost</code>, <code>127.*</code>,
 * <code>[::1]</code>) always bypass the proxy, mirroring the JDK default
 * <code>http.nonProxyHosts</code> behaviour. CIDR notation entries are not supported.
 *
 * <p><strong>Why an explicit Authenticator?</strong><br>
 * {@link java.net.http.HttpClient} ignores {@link Authenticator#setDefault(Authenticator)};
 * proxy credentials only take effect when supplied via
 * {@link java.net.http.HttpClient.Builder#authenticator(Authenticator)}. The authenticator
 * returned by {@link #toAuthenticator()} releases credentials only for
 * {@link Authenticator.RequestorType#PROXY} requests that match the configured proxy
 * host and port, never for origin-server challenges.
 *
 * <p><strong>Basic auth over HTTPS tunnels:</strong><br>
 * The JDK disables the Basic scheme for HTTPS CONNECT tunnelling by default via
 * <code>jdk.http.auth.tunneling.disabledSchemes=Basic</code> (see
 * <code>$JAVA_HOME/conf/net.properties</code>). For proxy credentials to be sent on the
 * CONNECT request of HTTPS traffic, that property must be cleared, e.g. with
 * <code>-Djdk.http.auth.tunneling.disabledSchemes=</code>.
 *
 * @see HxClient.Builder#proxy(ProxySelector)
 * @see HxClient.Builder#authenticator(Authenticator)
 */
public class HxProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(HxProxyConfig.class);

    /**
     * Connection details for a single proxy server, with optional credentials.
     */
    static final class ProxyEntry {
        final String host;
        final int port;
        final String username;
        final String password;

        ProxyEntry(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }

        boolean hasCredentials() {
            return username != null && !username.isEmpty();
        }

        InetSocketAddress address() {
            return InetSocketAddress.createUnresolved(host, port);
        }
    }

    private final ProxyEntry httpProxy;
    private final ProxyEntry httpsProxy;
    private final List<String> noProxyHosts;

    private HxProxyConfig(ProxyEntry httpProxy, ProxyEntry httpsProxy, List<String> noProxyHosts) {
        this.httpProxy = httpProxy;
        this.httpsProxy = httpsProxy;
        this.noProxyHosts = noProxyHosts;
    }

    /**
     * Creates a builder to assemble a proxy configuration from explicit values, for callers that
     * resolve proxy settings themselves rather than from the environment.
     *
     * @return a new {@link Builder}
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for {@link HxProxyConfig}: the proxy details are supplied explicitly
     * (host, port and optional credentials per protocol, plus {@code NO_PROXY} entries).
     */
    public static final class Builder {
        private ProxyEntry httpProxy;
        private ProxyEntry httpsProxy;
        private List<String> noProxyHosts = List.of();

        public Builder httpProxy(String host, int port, String username, String password) {
            this.httpProxy = host != null ? new ProxyEntry(host, port, username, password) : null;
            return this;
        }

        public Builder httpsProxy(String host, int port, String username, String password) {
            this.httpsProxy = host != null ? new ProxyEntry(host, port, username, password) : null;
            return this;
        }

        public Builder noProxy(List<String> hosts) {
            this.noProxyHosts = hosts == null
                    ? List.of()
                    : hosts.stream()
                            .filter(h -> h != null)
                            .map(h -> h.trim().toLowerCase(Locale.ROOT))
                            .filter(h -> !h.isEmpty())
                            .toList();
            return this;
        }

        public HxProxyConfig build() {
            return new HxProxyConfig(httpProxy, httpsProxy, noProxyHosts);
        }
    }

    /**
     * Creates a {@link ProxySelector} that routes requests through the configured proxies,
     * bypassing hosts matched by {@code NO_PROXY} and loopback addresses.
     *
     * @return a new ProxySelector reflecting this configuration
     */
    public ProxySelector toProxySelector() {
        // precompute the proxy lists - select() runs once per outbound request
        final List<Proxy> direct = List.of(Proxy.NO_PROXY);
        final List<Proxy> viaHttpProxy = httpProxy != null
                ? List.of(new Proxy(Proxy.Type.HTTP, httpProxy.address()))
                : direct;
        final List<Proxy> viaHttpsProxy = httpsProxy != null
                ? List.of(new Proxy(Proxy.Type.HTTP, httpsProxy.address()))
                : direct;
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (uri == null || isBypassed(uri.getHost()))
                    return direct;
                return "https".equalsIgnoreCase(uri.getScheme()) ? viaHttpsProxy : viaHttpProxy;
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                log.debug("Failed to connect to proxy {} for {}: {}", sa, uri, ioe.getMessage());
            }
        };
    }

    /**
     * Creates an {@link Authenticator} that supplies the configured proxy credentials.
     *
     * <p>Credentials are released only for {@link Authenticator.RequestorType#PROXY}
     * requests whose host and port match the configured proxy, never for origin-server
     * authentication challenges.
     *
     * @return a new Authenticator, or null when no proxy credentials are configured
     */
    public Authenticator toAuthenticator() {
        if (!hasCredentials())
            return null;
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY)
                    return null;
                final ProxyEntry entry = credentialsFor(getRequestingHost(), getRequestingPort());
                if (entry == null)
                    return null;
                final String password = entry.password != null ? entry.password : "";
                return new PasswordAuthentication(entry.username, password.toCharArray());
            }
        };
    }

    /**
     * @return true when at least one configured proxy carries credentials
     */
    public boolean hasCredentials() {
        return (httpProxy != null && httpProxy.hasCredentials())
                || (httpsProxy != null && httpsProxy.hasCredentials());
    }

    ProxyEntry getHttpProxy() {
        return httpProxy;
    }

    ProxyEntry getHttpsProxy() {
        return httpsProxy;
    }

    /**
     * Determines whether the given target host must bypass the proxy, either because it is
     * a loopback address or because it matches a {@code NO_PROXY} entry.
     */
    boolean isBypassed(String host) {
        if (host == null)
            return true;
        final String target = host.toLowerCase(Locale.ROOT);
        // always bypass loopback targets, consistent with the JDK default `http.nonProxyHosts`
        if (target.equals("localhost") || target.startsWith("127.") || target.equals("::1") || target.equals("[::1]"))
            return true;
        for (String entry : noProxyHosts) {
            if (entry.equals("*"))
                return true;
            // "*.example.com" and ".example.com" match sub-domains only;
            // "example.com" matches the host itself and any sub-domain
            final String suffix = entry.startsWith("*.") ? entry.substring(1) : entry;
            if (suffix.startsWith(".")) {
                if (target.endsWith(suffix))
                    return true;
            }
            else if (target.equals(suffix) || target.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private ProxyEntry credentialsFor(String host, int port) {
        for (ProxyEntry entry : new ProxyEntry[]{httpsProxy, httpProxy}) {
            if (entry != null && entry.hasCredentials() && entry.host.equalsIgnoreCase(host) && entry.port == port)
                return entry;
        }
        return null;
    }
}
