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

## Limitations

Validation resolves DNS at call time; a caller that later opens a connection
resolves DNS again, leaving a TOCTOU / DNS-rebinding window. Pin the resolved
address if that gap matters for your use case.
