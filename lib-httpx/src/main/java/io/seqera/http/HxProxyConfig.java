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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper that resolves HTTP/HTTPS forward-proxy settings from the standard environment
 * variables and Java system properties, and exposes them as a {@link ProxySelector} and
 * a proxy-only {@link Authenticator} suitable for {@link java.net.http.HttpClient}.
 *
 * <p><strong>Configuration sources</strong> (first match wins, per target protocol):
 * <ul>
 *   <li><code>HTTPS_PROXY</code> / <code>https_proxy</code> — proxy for <code>https</code> targets</li>
 *   <li><code>HTTP_PROXY</code> / <code>http_proxy</code> — proxy for <code>http</code> targets</li>
 *   <li><code>ALL_PROXY</code> / <code>all_proxy</code> — fallback for both protocols</li>
 *   <li><code>https.proxyHost</code>/<code>https.proxyPort</code> and
 *       <code>http.proxyHost</code>/<code>http.proxyPort</code> system properties — last resort</li>
 * </ul>
 *
 * <p>Proxy values are parsed as URLs and may embed URL-encoded credentials, e.g.
 * <code>http://user:p%40ss@proxy.example.com:8080</code>. The <code>NO_PROXY</code> /
 * <code>no_proxy</code> variable is honoured as a comma-separated list of host names or
 * domain suffixes (optionally prefixed with <code>.</code> or <code>*.</code>); the single
 * entry <code>*</code> disables proxying entirely. Loopback targets (<code>localhost</code>,
 * <code>127.*</code>, <code>[::1]</code>) always bypass the proxy, mirroring the JDK default
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
 * @see HxClient.Builder#proxyAuthenticator(Authenticator)
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
     * Resolves the proxy configuration from the process environment variables and
     * Java system properties.
     *
     * @return the detected proxy configuration, or null when no proxy is configured
     */
    public static HxProxyConfig fromEnvironment() {
        return fromEnvironment(System.getenv(), System.getProperties());
    }

    /**
     * Resolves the proxy configuration from the given environment and system properties.
     * Visible for testing.
     *
     * @param env the environment variables to inspect
     * @param props the system properties to use as fallback
     * @return the detected proxy configuration, or null when no proxy is configured
     */
    static HxProxyConfig fromEnvironment(Map<String, String> env, Properties props) {
        final ProxyEntry allProxy = parseProxyUrl(getVar(env, "ALL_PROXY"));

        ProxyEntry httpsProxy = parseProxyUrl(getVar(env, "HTTPS_PROXY"));
        if (httpsProxy == null)
            httpsProxy = allProxy;
        if (httpsProxy == null)
            httpsProxy = fromSystemProperties(props, "https", 443);

        ProxyEntry httpProxy = parseProxyUrl(getVar(env, "HTTP_PROXY"));
        if (httpProxy == null)
            httpProxy = allProxy;
        if (httpProxy == null)
            httpProxy = fromSystemProperties(props, "http", 80);

        if (httpProxy == null && httpsProxy == null)
            return null;

        return new HxProxyConfig(httpProxy, httpsProxy, parseNoProxy(getVar(env, "NO_PROXY")));
    }

    /**
     * Creates a {@link ProxySelector} that routes requests through the configured proxies,
     * bypassing hosts matched by {@code NO_PROXY} and loopback addresses.
     *
     * @return a new ProxySelector reflecting this configuration
     */
    public ProxySelector toProxySelector() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                final ProxyEntry entry = proxyFor(uri);
                return entry != null
                        ? List.of(new Proxy(Proxy.Type.HTTP, entry.address()))
                        : List.of(Proxy.NO_PROXY);
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

    List<String> getNoProxyHosts() {
        return noProxyHosts;
    }

    /**
     * Determines the proxy to use for the given target URI, or null to connect directly.
     */
    ProxyEntry proxyFor(URI uri) {
        if (uri == null || isBypassed(uri.getHost()))
            return null;
        return "https".equalsIgnoreCase(uri.getScheme()) ? httpsProxy : httpProxy;
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

    /**
     * Looks up an environment variable checking the upper-case name first, then the lower-case one.
     */
    private static String getVar(Map<String, String> env, String name) {
        final String value = env.get(name);
        if (value != null && !value.isBlank())
            return value;
        return env.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Parses a proxy URL such as {@code http://user:pass@host:port}, URL-decoding any
     * embedded credentials. A bare {@code host:port} value is accepted as well.
     *
     * @param value the proxy setting value, may be null
     * @return the parsed entry, or null when the value is missing or malformed
     */
    static ProxyEntry parseProxyUrl(String value) {
        if (value == null || value.isBlank())
            return null;
        try {
            String spec = value.trim();
            if (!spec.contains("://"))
                spec = "http://" + spec;
            final URI uri = new URI(spec);
            final String host = uri.getHost();
            if (host == null) {
                log.warn("Ignoring proxy setting with no valid host - check the HTTP_PROXY/HTTPS_PROXY environment variables");
                return null;
            }
            int port = uri.getPort();
            if (port == -1)
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            String username = null;
            String password = null;
            final String userInfo = uri.getRawUserInfo();
            if (userInfo != null) {
                final int sep = userInfo.indexOf(':');
                username = urlDecode(sep == -1 ? userInfo : userInfo.substring(0, sep));
                password = sep == -1 ? null : urlDecode(userInfo.substring(sep + 1));
            }
            return new ProxyEntry(host, port, username, password);
        }
        catch (URISyntaxException e) {
            // do not include the value in the message, it may embed credentials
            log.warn("Ignoring malformed proxy setting - check the HTTP_PROXY/HTTPS_PROXY environment variables");
            return null;
        }
    }

    private static ProxyEntry fromSystemProperties(Properties props, String protocol, int defaultPort) {
        final String host = props.getProperty(protocol + ".proxyHost");
        if (host == null || host.isBlank())
            return null;
        int port = defaultPort;
        final String portValue = props.getProperty(protocol + ".proxyPort");
        if (portValue != null && !portValue.isBlank()) {
            try {
                port = Integer.parseInt(portValue.trim());
            }
            catch (NumberFormatException e) {
                log.warn("Ignoring invalid {}.proxyPort system property: {}", protocol, portValue);
            }
        }
        return new ProxyEntry(host.trim(), port, null, null);
    }

    static List<String> parseNoProxy(String value) {
        if (value == null || value.isBlank())
            return List.of();
        final List<String> result = new ArrayList<>();
        for (String entry : value.split(",")) {
            final String item = entry.trim().toLowerCase(Locale.ROOT);
            if (!item.isEmpty())
                result.add(item);
        }
        return List.copyOf(result);
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
