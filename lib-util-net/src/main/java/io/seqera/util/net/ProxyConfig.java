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

package io.seqera.util.net;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable HTTP/HTTPS forward (egress) proxy configuration resolved from a proxy URI or from the
 * {@code HTTP_PROXY}/{@code HTTPS_PROXY}/{@code NO_PROXY} environment variables, exposed as a
 * {@link ProxySelector} and a proxy-scoped {@link Authenticator} suitable for a
 * {@link java.net.http.HttpClient}.
 *
 * <p>Parsing, credential-decoding, no-proxy and Basic-over-CONNECT semantics mirror Nextflow's
 * {@code nextflow.util.ProxyConfig} (the source of truth), so products sharing this class share the
 * same proxy behaviour. This class depends only on the JDK (and the slf4j-api logging facade); it
 * never reads {@link System#getenv()} on its own — the caller passes the URI or environment map in.
 *
 * <p>{@code NO_PROXY} entries are matched as host names or domain suffixes (optionally prefixed with
 * {@code .} or {@code *.}); the single entry {@code *} disables proxying entirely, and loopback
 * targets always bypass the proxy. CIDR notation is not supported.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public final class ProxyConfig {

    private static final Logger log = LoggerFactory.getLogger(ProxyConfig.class);

    /** A single proxy endpoint with optional Basic credentials. */
    private record Endpoint(String host, int port, String username, String password) {
        boolean hasCredentials() {
            return username != null && !username.isEmpty();
        }
        InetSocketAddress address() {
            return InetSocketAddress.createUnresolved(host, port);
        }
    }

    private final Endpoint httpProxy;    // nullable
    private final Endpoint httpsProxy;   // nullable
    private final List<String> noProxyHosts;

    private ProxyConfig(Endpoint httpProxy, Endpoint httpsProxy, List<String> noProxyHosts) {
        this.httpProxy = httpProxy;
        this.httpsProxy = httpsProxy;
        this.noProxyHosts = normalizeNoProxy(noProxyHosts);
    }

    // ------------------------------------------------------------------ factories

    /**
     * Resolve a proxy applied to both http and https destinations from a single URI.
     *
     * @param uri A proxy URI e.g. {@code http://user:pass@proxy:3128}, or {@code null}/empty for none
     * @return The corresponding {@link ProxyConfig}, or {@code null} when {@code uri} is empty
     */
    public static ProxyConfig fromUri(String uri) {
        return fromUri(uri, null, null, null);
    }

    /**
     * Resolve a proxy applied to both http and https destinations from a single URI. When an explicit
     * {@code username} is provided it (with {@code password}) takes precedence over any credentials
     * embedded in the URI.
     *
     * @param uri A proxy URI e.g. {@code http://user:pass@proxy:3128}, or {@code null}/empty for none
     * @param username Proxy username overriding the URI user-info; may be {@code null}
     * @param password Proxy password used together with an explicit {@code username}; may be {@code null}
     * @param noProxy Hosts that must bypass the proxy; may be {@code null}
     * @return The corresponding {@link ProxyConfig}, or {@code null} when {@code uri} is empty
     */
    public static ProxyConfig fromUri(String uri, String username, String password, List<String> noProxy) {
        final Parsed p = parse(uri);
        if( p == null )
            return null;
        final boolean explicit = username != null && !username.isEmpty();
        final String user = explicit ? username : p.username();
        final String pass = explicit ? password : p.password();
        final int port = portAsInt(p.port(), "https".equalsIgnoreCase(p.protocol()) ? 443 : 80);
        final Endpoint ep = new Endpoint(p.host(), port, user, pass);
        final ProxyConfig cfg = new ProxyConfig(ep, ep, noProxy);
        log.debug("Proxy config from uri: {}", cfg);
        return cfg;
    }

    /**
     * Resolve per-protocol proxies from a {@code HTTP_PROXY}/{@code HTTPS_PROXY}/{@code NO_PROXY}
     * environment map (upper- and lower-case names, with an {@code ALL_PROXY} fallback). The caller
     * supplies the map — this method never reads {@link System#getenv()} itself.
     *
     * @param env The environment variables map
     * @return The corresponding {@link ProxyConfig}, or {@code null} when no proxy variable is present
     */
    public static ProxyConfig fromEnvironment(Map<String,String> env) {
        final Parsed http = parse(firstNonEmpty(env, "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"));
        final Parsed https = parse(firstNonEmpty(env, "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"));
        if( http == null && https == null )
            return null;
        final Endpoint httpEp = http != null
                ? new Endpoint(http.host(), portAsInt(http.port(), 80), http.username(), http.password())
                : null;
        final Endpoint httpsEp = https != null
                ? new Endpoint(https.host(), portAsInt(https.port(), 443), https.username(), https.password())
                : null;
        final ProxyConfig cfg = new ProxyConfig(httpEp, httpsEp, split(firstNonEmpty(env, "NO_PROXY", "no_proxy")));
        log.debug("Proxy config from environment: {}", cfg);
        return cfg;
    }

    // ------------------------------------------------------------------ java.net views

    /**
     * @return {@code true} when at least one configured proxy carries credentials
     */
    public boolean hasCredentials() {
        return (httpProxy != null && httpProxy.hasCredentials())
                || (httpsProxy != null && httpsProxy.hasCredentials());
    }

    /**
     * @return A {@link ProxySelector} routing per scheme, bypassing loopback and {@code NO_PROXY} targets
     */
    public ProxySelector toProxySelector() {
        // precompute the proxy lists - select() runs once per outbound request
        final List<Proxy> direct = List.of(Proxy.NO_PROXY);
        final List<Proxy> viaHttp = httpProxy != null
                ? List.of(new Proxy(Proxy.Type.HTTP, httpProxy.address()))
                : direct;
        final List<Proxy> viaHttps = httpsProxy != null
                ? List.of(new Proxy(Proxy.Type.HTTP, httpsProxy.address()))
                : direct;
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if( uri == null || isBypassed(uri.getHost()) )
                    return direct;
                return "https".equalsIgnoreCase(uri.getScheme()) ? viaHttps : viaHttp;
            }
            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException e) {
                log.debug("Failed to connect to proxy {} for {}: {}", sa, uri, e.getMessage());
            }
        };
    }

    /**
     * Creates a proxy-scoped {@link Authenticator}, or {@code null} when no credentials are configured.
     *
     * <p>Credentials are released only for {@link Authenticator.RequestorType#PROXY} challenges whose
     * host and port match a configured proxy — never for origin-server challenges. Matching on host+port
     * (not protocol) inherently covers the HTTPS {@code CONNECT} tunnel, where the JDK reports the
     * requesting protocol as {@code http} even for an https destination.
     *
     * @return A new {@link Authenticator}, or {@code null} when no proxy credentials are configured
     */
    public Authenticator toAuthenticator() {
        if( !hasCredentials() )
            return null;
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if( getRequestorType() != RequestorType.PROXY )
                    return null;
                final Endpoint ep = credentialsFor(getRequestingHost(), getRequestingPort());
                if( ep == null )
                    return null;
                final String pass = ep.password() != null ? ep.password() : "";
                return new PasswordAuthentication(ep.username(), pass.toCharArray());
            }
        };
    }

    /**
     * Determines whether the given target host must bypass the proxy, because it is a loopback address
     * or matches a {@code NO_PROXY} entry.
     *
     * @param host The target host name
     * @return {@code true} when the target must be reached directly
     */
    public boolean isBypassed(String host) {
        if( host == null )
            return true;
        final String target = host.toLowerCase(Locale.ROOT);
        // always bypass loopback targets, consistent with the JDK default `http.nonProxyHosts`
        if( target.equals("localhost") || target.startsWith("127.") || target.equals("::1") || target.equals("[::1]") )
            return true;
        for( String entry : noProxyHosts ) {
            if( entry.equals("*") )
                return true;
            // "*.example.com" and ".example.com" match sub-domains only;
            // "example.com" matches the host itself and any sub-domain
            final String suffix = entry.startsWith("*.") ? entry.substring(1) : entry;
            if( suffix.startsWith(".") ) {
                if( target.endsWith(suffix) )
                    return true;
            }
            else if( target.equals(suffix) || target.endsWith("." + suffix) ) {
                return true;
            }
        }
        return false;
    }

    private Endpoint credentialsFor(String host, int port) {
        if( httpsProxy != null && httpsProxy.hasCredentials() && httpsProxy.host().equalsIgnoreCase(host) && httpsProxy.port() == port )
            return httpsProxy;
        if( httpProxy != null && httpProxy.hasCredentials() && httpProxy.host().equalsIgnoreCase(host) && httpProxy.port() == port )
            return httpProxy;
        return null;
    }

    // ------------------------------------------------------------------ Basic-over-CONNECT toggle

    /**
     * The JDK disables the Basic scheme for proxy authentication over HTTPS {@code CONNECT} tunnelling
     * by default ({@code jdk.http.auth.tunneling.disabledSchemes=Basic}), which blocks authenticating
     * proxies for {@code https} targets. This clears that property so Basic credentials reach the proxy,
     * but only when it is unset — an operator's explicit value (e.g. via {@code JAVA_TOOL_OPTIONS}) wins.
     *
     * <p>Call once at startup, before the first outbound request: the JDK reads this property lazily and
     * only once.
     *
     * @return {@code true} when the property was changed, {@code false} when it was already set
     */
    public static boolean enableBasicProxyTunneling() {
        final String key = "jdk.http.auth.tunneling.disabledSchemes";
        if( System.getProperty(key) == null ) {
            System.setProperty(key, "");
            log.debug("Cleared '{}' to allow Basic proxy authentication over HTTPS tunnelling", key);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ parsing (source of truth: nextflow.util.ProxyConfig)

    /**
     * The components of a parsed proxy URI. Percent-encoded {@code username}/{@code password} are
     * decoded; any path/query in the URI is ignored. Fields not present in the input are {@code null}.
     */
    public record Parsed(String protocol, String host, String port, String username, String password) { }

    /**
     * Parse a proxy string retrieving its protocol, host, port, username and password components.
     * Exposed so callers that need the individual components (e.g. to set {@code -Dhttp.proxyHost}
     * system properties) can reuse the same parsing instead of duplicating it.
     *
     * @param value A proxy string e.g. {@code host}, {@code host:port}, {@code scheme://host:port}
     *      or {@code scheme://user:pass@host:port}
     * @return The parsed components, or {@code null} when {@code value} is empty
     * @throws IllegalArgumentException when {@code value} is not a valid proxy URL
     */
    public static Parsed parse(String value) {
        if( value == null || value.isEmpty() )
            return null;
        try {
            if( value.contains("://") ) {
                final URL url = new URL(value);
                String user = null, pass = null;
                final String info = url.getUserInfo();
                final int p = info != null ? info.indexOf(':') : -1;
                if( p != -1 ) {
                    user = decodeUserInfo(info.substring(0, p));
                    pass = decodeUserInfo(info.substring(p + 1));
                }
                final String port = url.getPort() > 0 ? String.valueOf(url.getPort()) : null;
                return new Parsed(url.getProtocol(), url.getHost(), port, user, pass);
            }
            final int p = value.indexOf(':');
            if( p != -1 )
                return new Parsed(null, value.substring(0, p), value.substring(p + 1), null, null);
            return new Parsed(null, value, null, null, null);
        }
        catch( MalformedURLException e ) {
            throw new IllegalArgumentException("Invalid proxy URL: " + value, e);
        }
    }

    /**
     * Percent-decode a userinfo component (username or password) per RFC 3986, so proxy credentials
     * carrying special characters (e.g. {@code @}, {@code :}) work. A literal {@code +} is preserved —
     * userinfo is not form-encoded — so it is shielded from the {@code +}→space rule of
     * {@link URLDecoder}.
     */
    private static String decodeUserInfo(String s) {
        return s != null ? URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8) : null;
    }

    private static int portAsInt(String port, int defaultPort) {
        if( port == null || port.isBlank() )
            return defaultPort;
        try {
            return Integer.parseInt(port.trim());
        }
        catch( NumberFormatException e ) {
            log.warn("Ignoring invalid proxy port '{}' - using default {}", port, defaultPort);
            return defaultPort;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> normalizeNoProxy(List<String> hosts) {
        if( hosts == null )
            return List.of();
        final List<String> result = new ArrayList<>();
        for( String h : hosts ) {
            if( h == null )
                continue;
            final String t = h.trim().toLowerCase(Locale.ROOT);
            if( !t.isEmpty() )
                result.add(t);
        }
        return List.copyOf(result);
    }

    private static List<String> split(String csv) {
        if( csv == null || csv.isBlank() )
            return List.of();
        final List<String> result = new ArrayList<>();
        for( String s : csv.split(",") )
            if( !s.isBlank() )
                result.add(s.trim());
        return result;
    }

    private static String firstNonEmpty(Map<String,String> env, String... keys) {
        for( String k : keys ) {
            final String v = env.get(k);
            if( v != null && !v.isEmpty() )
                return v;
        }
        return null;
    }

    @Override
    public String toString() {
        return "ProxyConfig[http=" + describe(httpProxy) + "; https=" + describe(httpsProxy) + "; noProxy=" + noProxyHosts + "]";
    }

    private static String describe(Endpoint ep) {
        // credentials are never rendered
        return ep == null ? "-" : ep.host() + ":" + ep.port() + (ep.hasCredentials() ? " (auth)" : "");
    }
}
