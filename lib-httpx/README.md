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
- **Multi-Session Auth**: Support for multiple concurrent authentication sessions with per-request token management
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
    .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL) // New: Configure cookie handling for token refresh
    .build();

// Using HxConfig directly (deprecated methods shown for compatibility)
HxConfig config = HxConfig.newBuilder()
    .bearerToken("your-jwt-token")  // Recommended
    // .withBearerToken("your-jwt-token")  // Deprecated but still works
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
            // Return base64-encoded credentials for Basic auth
            return Base64.getEncoder().encodeToString("user:pass".getBytes());
        } else if (scheme == AuthenticationScheme.BEARER) {
            // Return bearer token
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

For applications managing multiple users or authentication contexts, use `HxAuth` to handle per-request authentication with automatic token refresh:

```java
// Create a shared client with refresh URL configured
HxClient client = HxClient.newBuilder()
    .refreshTokenUrl("https://api.example.com/oauth/token")
    .build();

// Create auth for each user session
HxAuth user1Auth = HxAuth.of("user1.jwt.token", "user1-refresh-token");
HxAuth user2Auth = HxAuth.of("user2.jwt.token", "user2-refresh-token");

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

**Features:**
- Each `HxAuth` maintains its own token pair (access + refresh)
- Automatic token refresh on 401 responses, scoped to each auth session
- Thread-safe concurrent refresh coordination per auth key
- Tokens are identified by SHA-256 hash of the access token

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
    .refreshTokenUrl("https://api.example.com/oauth/token")
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

### New Cookie Policy Configuration (v2.1.0+)

Configure cookie handling for JWT token refresh operations:

```java
// Configure cookie policy for token refresh operations
HxClient client = HxClient.newBuilder()
    .bearerToken("your-jwt-token")
    .refreshToken("your-refresh-token") 
    .refreshTokenUrl("https://api.example.com/oauth/token")
    .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)  // Accept all cookies
    // .refreshCookiePolicy(CookiePolicy.ACCEPT_NONE)  // Accept no cookies
    // .refreshCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER)  // Accept only from original server
    .build();
```

## Migration Guide (v2.0.0 → v2.1.0)

Version 2.1.0 introduces new setter methods without the `with` prefix to align with HxClient.Builder patterns. The old `with` prefixed methods are deprecated but still work:

```java
// ❌ Deprecated (still works with warnings)
HxConfig config = HxConfig.newBuilder()
    .withBearerToken("token")
    .withMaxAttempts(3)
    .withRefreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
    .build();

// ✅ Recommended (new in v2.1.0)
HxConfig config = HxConfig.newBuilder()
    .bearerToken("token")
    .maxAttempts(3)
    .refreshCookiePolicy(CookiePolicy.ACCEPT_ALL)
    .build();

// ✅ Best practice: Use HxClient.Builder directly
HxClient client = HxClient.newBuilder()
    .bearerToken("token")
    .maxAttempts(3)
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
| `refreshCookiePolicy` | Cookie policy for JWT token refresh operations (ACCEPT_ALL, ACCEPT_NONE, ACCEPT_ORIGINAL_SERVER) | null |
| `wwwAuthentication` | Enable WWW-Authenticate challenge handling | false |
| `wwwAuthenticationCallback` | Callback for providing authentication credentials | null |

## API Documentation

All classes and methods include comprehensive Javadoc documentation covering:

- **Class-level documentation**: Complete overview of functionality and usage patterns
- **Method documentation**: Detailed parameter descriptions, return values, and behavior
- **Usage examples**: Code samples showing common use cases
- **Thread safety notes**: Concurrency guarantees and synchronization details
- **Error handling**: Exception types and retry behavior

### Key Classes

- **`HxClient`** (Http eXtended Client): Main client class with retry, JWT, and WWW-Authenticate functionality
- **`HxConfig`**: Configuration builder with all available options including Retryable.Config integration
- **`HxAuth`**: Immutable container for JWT access token and refresh token pairs
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
