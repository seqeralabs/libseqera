# lib-util-http

HTTP utilities for debugging and manipulating HTTP requests and responses. Provides helper methods for dumping headers, parameters, and masking sensitive data.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-util-http:1.0.0'
}
```

## Features

- **Header Dumping**: Format HTTP headers for debugging from Micronaut or standard Java HTTP clients
- **Parameter Dumping**: Format query parameters and form data for logging
- **Sensitive Data Masking**: Automatically mask sensitive values like tokens, secrets, and passwords
- **Multi-Client Support**: Works with Micronaut HttpRequest/HttpResponse and Java's java.net.http classes
- **Debug-Friendly Output**: Readable, formatted output for logs

## Usage

### Dumping HTTP Headers

```java
import io.seqera.util.http.HttpUtil;

// Micronaut HTTP client
io.micronaut.http.HttpRequest<?> request = HttpRequest.GET("/api/data");
log.debug("Request headers: {}", HttpUtil.dumpHeaders(request));

io.micronaut.http.HttpResponse<?> response = client.toBlocking().exchange(request);
log.debug("Response headers: {}", HttpUtil.dumpHeaders(response));

// Standard Java HTTP client
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .header("Authorization", "Bearer token123")
    .GET()
    .build();
log.debug("Request headers: {}", HttpUtil.dumpHeaders(request));

HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
log.debug("Response headers: {}", HttpUtil.dumpHeaders(response));
```

**Output format:**
```
Request headers:
  Content-Type=application/json
  Authorization=Bearer token123
  Accept=application/json
```

### Dumping Query Parameters

```java
import io.seqera.util.http.HttpUtil;

// Micronaut request
io.micronaut.http.HttpRequest<?> request = HttpRequest.GET("/oauth/callback?code=abc123&state=xyz789");
log.debug("Parameters: {}", HttpUtil.dumpParams(request));

// Generic Map
Map<String, List<String>> params = Map.of(
    "code", List.of("auth_code_123"),
    "state", List.of("secure_state_456"),
    "redirect_uri", List.of("http://localhost:8080/callback")
);
log.debug("Parameters: {}", HttpUtil.dumpParams(params));
```

**Output format:**
```
Parameters:
  code=auth_code_123
  state=secure_state_456
  redirect_uri=http://localhost:8080/callback
```

### Masking Sensitive Data

Prevent sensitive information from appearing in logs by masking values. The utility automatically truncates sensitive fields to the first 10 characters followed by "...".

```java
import io.seqera.util.http.HttpUtil;

// Using default sensitive keys
Map<String, Object> formData = Map.of(
    "client_id", "my-client",
    "client_secret", "super-secret-value-12345",
    "grant_type", "authorization_code",
    "code", "auth_code_abcdefghijklmnop",
    "redirect_uri", "http://localhost:8080/callback"
);

Map<String, Object> masked = HttpUtil.maskParams(formData);
log.debug("OAuth request: {}", masked);
// Output: {client_id=my-client, client_secret=super-secr..., grant_type=authorization_code,
//          code=auth_code_..., redirect_uri=http://localhost:8080/callback}

// Using custom sensitive keys
List<String> customKeys = List.of("api_key", "secret", "token");
Map<String, Object> data = Map.of(
    "api_key", "key_1234567890abcdef",
    "username", "john.doe"
);
Map<String, Object> customMasked = HttpUtil.maskParams(data, customKeys);
log.debug("API request: {}", customMasked);
// Output: {api_key=key_123456..., username=john.doe}
```

**Default sensitive keys:**
- `client_secret` - OAuth client secret
- `code` - OAuth authorization code
- `refresh_token` - OAuth refresh token
- `access_token` - OAuth access token
- `password` - User password

## API Reference

### Header Dumping Methods

| Method | Description |
|--------|-------------|
| `dumpHeaders(io.micronaut.http.HttpRequest<?>)` | Dump headers from Micronaut request |
| `dumpHeaders(io.micronaut.http.HttpResponse<?>)` | Dump headers from Micronaut response |
| `dumpHeaders(java.net.http.HttpRequest)` | Dump headers from standard Java request |
| `dumpHeaders(java.net.http.HttpResponse<?>)` | Dump headers from standard Java response |
| `dumpHeaders(Map<String, List<String>>)` | Dump headers from generic Map |

### Parameter Dumping Methods

| Method | Description |
|--------|-------------|
| `dumpParams(io.micronaut.http.HttpRequest<?>)` | Dump parameters from Micronaut request |
| `dumpParams(Map<String, List<String>>)` | Dump parameters from generic Map |

### Sensitive Data Masking Methods

| Method | Description |
|--------|-------------|
| `maskParams(Map<String, Object>)` | Mask sensitive data using default keys |
| `maskParams(Map<String, Object>, List<String>)` | Mask sensitive data using custom keys |

## Dependencies

- **Micronaut HTTP**: For Micronaut HTTP client integration
- **Java 17+**: Uses standard java.net.http package

## Common Use Cases

### Debugging OAuth Flows

```java
// Log OAuth callback parameters safely
io.micronaut.http.HttpRequest<?> request = HttpRequest.GET("/oauth/callback?code=...&state=...");
log.debug("OAuth callback - params: {}", HttpUtil.dumpParams(request));

// Log OAuth token exchange safely
Map<String, Object> tokenRequest = Map.of(
    "grant_type", "authorization_code",
    "code", authCode,
    "client_id", clientId,
    "client_secret", clientSecret,
    "redirect_uri", redirectUri
);
log.debug("Token exchange request: {}", HttpUtil.maskParams(tokenRequest));
```

### Debugging API Requests

```java
// Log all request details for troubleshooting
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .header("Authorization", "Bearer " + token)
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .build();

log.debug("API request to {} - headers: {}",
    request.uri(),
    HttpUtil.dumpHeaders(request));
```

### Debugging Response Issues

```java
HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

if (response.statusCode() >= 400) {
    log.error("API request failed with status {} - headers: {}",
        response.statusCode(),
        HttpUtil.dumpHeaders(response));
}
```

## Testing

```bash
./gradlew :lib-util-http:test
```
