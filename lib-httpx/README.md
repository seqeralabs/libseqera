# lib-httpx

Enhanced HTTP client extension for Java `HttpClient` with built-in retry logic and JWT token refresh capabilities.

## Installation

### Gradle

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-httpx:2.1.0'
}
```

**Note:** Check the project's `VERSION` file for the current version number.

## Features

- **Retry Logic**: Automatic retry for configurable HTTP status codes (default: 429, 500, 502, 503, 504)
- **Authentication Support**: Built-in support for JWT Bearer tokens and HTTP Basic authentication
- **JWT Token Refresh**: Automatic JWT token refresh when receiving 401 Unauthorized responses with configurable cookie policies
- **Multi-Session Auth**: Support for multiple concurrent authentication sessions via the `HxAuth` interface
- **Custom Token Storage**: Pluggable token store interface for distributed deployments (Redis, database, etc.)
- **WWW-Authenticate Support**: Automatic handling of HTTP authentication challenges (Basic and Bearer schemes)
- **Anonymous Authentication**: Fallback to anonymous authentication when credentials aren't provided
- **Configurable**: Customizable retry policies, timeouts, token refresh, authentication settings, and cookie policies
- **Generic Integration**: Compatible with any `Retryable.Config` for flexible retry configuration
- **Thread-safe**: Safe for concurrent use with atomic token refresh coordination
- **Async Support**: Support for both synchronous and asynchronous requests

## Usage

### Basic Usage

```java
// Create with default configuration
HxClient client = HxClient.newHxClient();

// Create with custom configuration using new builder pattern
HxClient client = HxClient.newBuilder()
    .maxAttempts(3)
    .retryStatusCodes(Set.of(429, 503))
    .build();

// Make HTTP requests
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .GET()
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

### Authentication Configuration

#### JWT Token Configuration

```java
// Using new builder pattern (recommended)
HxClient client = HxClient.newBuilder()
    .bearerToken("your-jwt-token")
    .refreshToken("your-refresh-token")
    .refreshTokenUrl("https://api.example.com/oauth/token")
    .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
    .build();

// Using HxConfig directly
HxConfig config = HxConfig.newBuilder()
    .bearerToken("your-jwt-token")
    .refreshToken("your-refresh-token")
    .refreshTokenUrl("https://api.example.com/oauth/token")
    .build();

HxClient client = HxClient.newBuilder().config(config).build();
```

#### Basic Authentication Configuration

```java
// Using HxClient builder (recommended)
HxClient client = HxClient.newBuilder()
    .basicAuth("your-username", "your-password")
    .build();

// Or using a pre-formatted token
HxClient client = HxClient.newBuilder()
    .basicAuth("username:password")
    .build();

// Using HxConfig directly
HxConfig config = HxConfig.newBuilder()
    .basicAuth("your-username", "your-password")
    .build();

HxClient client = HxClient.newBuilder().config(config).build();
```

**Authentication Constraints:**
- Cannot configure both JWT and Basic authentication simultaneously
- Choose one authentication method - the configuration builder will reject conflicting setups
- Use JWT tokens for modern APIs, Basic auth for legacy systems

### WWW-Authenticate Configuration

The client can automatically handle HTTP authentication challenges (401 responses with WWW-Authenticate headers):

```java
// Enable WWW-Authenticate handling with anonymous authentication fallback
HxConfig config = HxConfig.newBuilder()
    .wwwAuthentication(true)
    .build();

HxClient client = HxClient.newBuilder().config(config).build();

// With custom authentication callback
HxConfig config = HxConfig.newBuilder()
    .wwwAuthentication(true)
    .wwwAuthenticationCallback((scheme, realm) -> {
        if (scheme == AuthenticationScheme.BASIC) {
            return Base64.getEncoder().encodeToString("user:pass".getBytes());
        } else if (scheme == AuthenticationScheme.BEARER) {
            return "your-bearer-token";
        }
        return null; // Fall back to anonymous auth
    })
    .build();

HxClient client = HxClient.newBuilder().config(config).build();
```

**Supported Authentication Schemes:**
- **Basic**: Supports both credential-based and anonymous authentication
- **Bearer**: Attempts to retrieve anonymous tokens from OAuth2 endpoints when no credentials are provided

**Anonymous Authentication:**
- **Basic**: Uses empty credentials (base64 encoded `:`)
- **Bearer**: Attempts to get anonymous tokens from the authentication endpoint using OAuth2 flow

### Multi-Session Authentication

For applications managing multiple users or authentication contexts, use the `HxAuth` interface for per-request authentication with automatic token refresh.

#### The `HxAuth` Interface

`HxAuth` represents an authentication session. The `id()` uniquely identifies the session and **must remain the same for the entire lifecycle** of the auth — even as the access token and refresh token change over time due to token refresh operations. This allows the client to track and coordinate refreshes for the same logical session.

| Method | Description |
|--------|-------------|
| `id()` | Stable identifier, constant for the auth lifecycle |
| `accessToken()` | The current JWT access token |
| `refreshToken()` | The refresh token (nullable) |
| `refreshUrl()` | Per-auth refresh URL override (nullable, falls back to global config) |
| `withToken(token)` | Returns an `HxAuth` with the updated access token (same `id`) |
| `withRefresh(refresh)` | Returns an `HxAuth` with the updated refresh token (same `id`) |

Implementations must ensure `id()` remains constant — `withToken` and `withRefresh` return new instances preserving the same `id`.

#### Custom `HxAuth` Implementation

Implement `HxAuth` to define the identity strategy and token lifecycle for your application (e.g., key by user ID or tenant). Each `HxAuth` can also carry its own `refreshUrl`, allowing different auth sessions to refresh against different endpoints. If `refreshUrl()` returns null, the global `refreshTokenUrl` from `HxConfig` is used as fallback.

```java
public class TenantAuth implements HxAuth {
    private final String tenantId;
    private final String accessToken;
    private final String refreshToken;

    public TenantAuth(String tenantId, String accessToken, String refreshToken) {
        this.tenantId = tenantId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
    }

    @Override public String id() { return tenantId; }  // stable across refreshes
    @Override public String accessToken() { return accessToken; }
    @Override public String refreshToken() { return refreshToken; }
    @Override public String refreshUrl() { return null; }  // use global config

    @Override
    public HxAuth withToken(String token) {
        return new TenantAuth(tenantId, token, refreshToken);
    }

    @Override
    public HxAuth withRefresh(String refresh) {
        return new TenantAuth(tenantId, accessToken, refresh);
    }
}

// Create a shared client
HxClient client = HxClient.newBuilder().build();

// Create auth for each user/tenant session
HxAuth user1Auth = new TenantAuth("tenant-1", "user1.jwt.token", "user1-refresh-token");
HxAuth user2Auth = new TenantAuth("tenant-2", "user2.jwt.token", "user2-refresh-token");

// Make requests with per-user authentication
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .GET()
    .build();

HttpResponse<String> response1 = client.send(request, user1Auth, HttpResponse.BodyHandlers.ofString());
HttpResponse<String> response2 = client.send(request, user2Auth, HttpResponse.BodyHandlers.ofString());

// Async requests also supported
CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, user1Auth, HttpResponse.BodyHandlers.ofString());
```

#### Custom Token Store

By default, tokens are stored in an in-memory `ConcurrentHashMap`. For distributed deployments, provide a custom `HxTokenStore`:

```java
// Implement custom store (e.g., Redis-backed)
public class RedisTokenStore implements HxTokenStore {
    @Override
    public HxAuth get(String key) { /* Redis GET */ }

    @Override
    public void put(String key, HxAuth auth) { /* Redis SET */ }

    @Override
    public HxAuth putIfAbsent(String key, HxAuth auth) {
        // Use Redis SETNX for atomic check-and-set
        // Return existing value if present, otherwise store and return auth
    }

    @Override
    public HxAuth remove(String key) { /* Redis DEL */ }
}

// Use custom store
HxTokenStore customStore = new RedisTokenStore();
HxClient client = HxClient.newBuilder()
    .tokenStore(customStore)
    .build();
```

### Custom Retry Configuration

```java
// Using HxClient builder (recommended)
HxClient client = HxClient.newBuilder()
    .maxAttempts(5)
    .retryDelay(Duration.ofSeconds(1))
    .build();

// Using HxConfig builder for advanced configuration
HxConfig config = HxConfig.newBuilder()
    .maxAttempts(5)
    .delay(Duration.ofSeconds(1))
    .maxDelay(Duration.ofMinutes(2))
    .jitter(0.5)
    .multiplier(2.0)
    .retryStatusCodes(Set.of(429, 500, 502, 503, 504))
    .build();

HxClient client = HxClient.newBuilder().config(config).build();
```

### Integration with Existing Retry Configuration

```java
// Use any existing Retryable.Config with HxClient builder
Retryable.Config retryConfig = Retryable.ofDefaults().config();
HxClient client = HxClient.newBuilder()
    .retryConfig(retryConfig)
    .bearerToken("your-jwt-token")
    .build();

// Or combine with HTTP-specific settings using HxConfig
HxConfig config = HxConfig.newBuilder()
    .retryConfig(retryConfig)
    .bearerToken("your-jwt-token")
    .retryStatusCodes(Set.of(429, 503))
    .build();

HxClient client = HxClient.newBuilder().config(config).build();
```

### Cookie Policy Configuration

Configure cookie handling for JWT token refresh operations:

```java
HxClient client = HxClient.newBuilder()
    .bearerToken("your-jwt-token")
    .refreshToken("your-refresh-token")
    .refreshTokenUrl("https://api.example.com/oauth/token")
    .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
    .build();
```

## Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `maxAttempts` | Maximum number of retry attempts | 5 |
| `delay` | Initial delay between retries | 500ms |
| `maxDelay` | Maximum delay between retries | 30s |
| `jitter` | Random jitter factor (0-1) | 0.25 |
| `multiplier` | Exponential backoff multiplier | 2.0 |
| `retryStatusCodes` | HTTP status codes to retry | [429, 500, 502, 503, 504] |
| `jwtToken` | JWT Bearer token for authentication | null |
| `refreshToken` | Refresh token for JWT renewal | null |
| `refreshTokenUrl` | URL for token refresh requests | null |
| `tokenRefreshTimeout` | Timeout for token refresh requests | 30s |
| `tokenStore` | Custom token store for multi-session authentication | HxMapTokenStore |
| `basicAuthToken` | Token for HTTP Basic authentication (username:password format) | null |
| `refreshCookiePolicy` | Cookie policy for JWT token refresh operations | null |
| `wwwAuthentication` | Enable WWW-Authenticate challenge handling | false |
| `wwwAuthenticationCallback` | Callback for providing authentication credentials | null |

## Key Classes

- **`HxClient`**: Main HTTP client with retry, JWT, and WWW-Authenticate functionality
- **`HxConfig`**: Configuration builder with all available options
- **`HxAuth`**: Interface for authentication credentials with stable identity across refreshes
- **`HxTokenStore`**: Interface for pluggable token storage (default: in-memory ConcurrentHashMap)
- **`HxTokenManager`**: Thread-safe JWT token lifecycle management with multi-session support
- **`AuthenticationChallenge`**: Represents a parsed WWW-Authenticate challenge
- **`AuthenticationScheme`**: Enumeration of supported authentication schemes (Basic, Bearer)
- **`AuthenticationCallback`**: Interface for providing authentication credentials
- **`WwwAuthenticateParser`**: RFC 7235 compliant parser for WWW-Authenticate headers

## Dependencies

- `lib-retry`: Provides the underlying retry mechanism using Failsafe
- `dev.failsafe:failsafe`: Core retry and circuit breaker library
- `com.google.code.gson:gson`: For JSON parsing in WWW-Authenticate anonymous token retrieval
