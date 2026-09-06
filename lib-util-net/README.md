# lib-util-net

Network utilities shared across Seqera projects. Pure Java, no external dependencies (slf4j-api only).

## Usage

Add the dependency to your `build.gradle`:

```groovy
implementation 'io.seqera:lib-util-net:<version>'
```

### SSRF host validation

`io.seqera.util.net.SsrfValidator` guards against Server-Side Request Forgery by
rejecting hostnames that resolve to internal or otherwise sensitive resources
before an HTTP request is made.

```java
import io.seqera.util.net.SsrfValidator;
import io.seqera.util.net.SsrfValidationException;

try {
    SsrfValidator.validateHost("registry-1.docker.io");
}
catch (SsrfValidationException e) {
    // reject the request
}
```

`validateHost(String)` accepts a bare hostname, `host:port`, bracketed IPv6
(`[::1]:8080`) or a full `http(s)://` URL, and throws `SsrfValidationException`
when the host:

- is a localhost variant (`localhost`, `0.0.0.0`, `::1`, …)
- resolves to a loopback, link-local, site-local (private) or IPv6 unique-local address
- resolves to a cloud metadata service IP (AWS `169.254.169.254`, ECS `169.254.170.2`, IMDSv2 IPv6)
- cannot be resolved (fail closed)

### Egress proxy configuration

`io.seqera.util.net.ProxyConfig` resolves an HTTP/HTTPS forward (egress) proxy —
including an authenticating one — from a proxy URI or from the
`HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` environment variables, and exposes it as a
`java.net.ProxySelector` and a proxy-scoped `java.net.Authenticator` for a
`java.net.http.HttpClient`. Parsing, no-proxy and Basic-over-`CONNECT` semantics
mirror Nextflow's `nextflow.util.ProxyConfig`.

```java
import io.seqera.util.net.ProxyConfig;
import java.net.http.HttpClient;

// from an explicit URI (applied to both http and https), or from the environment
ProxyConfig proxy = ProxyConfig.fromUri("http://user:pass@proxy.example.com:3128");
// ProxyConfig proxy = ProxyConfig.fromEnvironment(System.getenv());

if (proxy != null) {
    var builder = HttpClient.newBuilder().proxy(proxy.toProxySelector());
    var auth = proxy.toAuthenticator();          // null when no credentials
    if (auth != null) builder.authenticator(auth);
    // for authenticating proxies on https targets, clear the JDK's default block
    // on Basic-over-CONNECT (no-op when the operator already set the property):
    if (proxy.hasCredentials()) ProxyConfig.enableBasicProxyTunneling();
    HttpClient client = builder.build();
}
```

`NO_PROXY` entries match host names or domain suffixes (optionally prefixed with
`.` or `*.`); `*` disables proxying entirely, and loopback targets always bypass
the proxy. CIDR notation is not supported.

To honour the proxy JVM-wide — installing the per-protocol `<proto>.proxyHost`/
`<proto>.proxyPort` and `http.nonProxyHosts` system properties and a default
`Authenticator` (also covering `FTP_PROXY`), the way a CLI launcher would — use
`ProxyConfig.setupFromEnvironment(System.getenv())`, which returns the same
http/https config for wiring `java.net.http` clients explicitly.

## Limitations

`SsrfValidator` resolves DNS at call time; a caller that later opens a connection
resolves DNS again, leaving a TOCTOU / DNS-rebinding window. Pin the resolved
address if that gap matters for your use case.
