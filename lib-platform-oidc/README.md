# lib-platform-oidc

OIDC Authorization Code + PKCE login flow for CLI clients authenticating against Seqera Platform.

## Usage

```java
import io.seqera.platform.auth.oidc.OidcLoginFlow;

String accessToken = OidcLoginFlow.builder()
    .endpoint("https://api.cloud.seqera.io")
    .clientId("nextflow_cli")
    .build()
    .login(url -> {
        // open the authorization URL in the user's browser
        Desktop.getDesktop().browse(URI.create(url));
    });
```

The `login` method:
1. Discovers OIDC endpoints from `{endpoint}/.well-known/openid-configuration`
2. Generates a PKCE code verifier and challenge
3. Starts a local callback server on `127.0.0.1` (ephemeral port)
4. Invokes the `browserLauncher` callback with the authorization URL
5. Waits for Platform to redirect the browser to the local callback
6. Exchanges the authorization code for an access token

## Gradle

```groovy
implementation 'io.seqera:lib-platform-oidc:0.1.0'
```

## Dependencies

None — uses only JDK classes (`java.net.http.HttpClient`, `com.sun.net.httpserver.HttpServer`, `java.security.*`).

## Testing

```bash
./gradlew :lib-platform-oidc:test
```
