# lib-httpx

Enhanced HTTP client extension for Java `HttpClient` with built-in retry logic and JWT token refresh capabilities.

## Installation

### Gradle

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-httpx:2.0.0'
}
```

**Note:** Check the project's `VERSION` file for the current version number.

## Features

- **Retry Logic**: Automatic retry for configurable HTTP status codes (default: 429, 500, 502, 503, 504)
- **Authentication Support**: Built-in support for JWT Bearer tokens and HTTP Basic authentication
- **JWT Token Refresh**: Automatic JWT token refresh when receiving 401 Unauthorized responses
- **WWW-Authenticate Support**: Automatic handling of HTTP authentication challenges (Basic and Bearer schemes)
- **Anonymous Authentication**: Fallback to anonymous authentication when credentials aren't provided
- **Configurable**: Customizable retry policies, timeouts, token refresh, and authentication settings
- **Generic Integration**: Compatible with any `Retryable.Config` for flexible retry configuration
- **Thread-safe**: Safe for concurrent use
- **Async Support**: Support for both synchronous and asynchronous requests

## Usage

### Basic Usage

```groovy
// Create with default configuration
def client = HxClient.create()

// Create with custom configuration
def config = HxConfig.builder()
    .withMaxAttempts(3)
    .withRetryStatusCodes([429, 503] as Set)
    .build()
def client = HxClient.create(config)

// Make HTTP requests
def request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .GET()
    .build()

def response = client.send(request, HttpResponse.BodyHandlers.ofString())
```

### Authentication Configuration

#### JWT Token Configuration

```groovy
def config = HxConfig.builder()
    .withJwtToken("your-jwt-token")
    .withRefreshToken("your-refresh-token")
    .withRefreshTokenUrl("https://api.example.com/oauth/token")
    .build()

def client = HxClient.create(config)
```

#### Basic Authentication Configuration

```groovy
// Using username and password
def config = HxConfig.builder()
    .withBasicAuth("your-username", "your-password")
    .build()

def client = HxClient.create(config)

// Or using a pre-formatted token
def config = HxConfig.builder()
    .withBasicAuth("username:password")
    .build()

def client = HxClient.create(config)
```

**Authentication Constraints:**
- Cannot configure both JWT and Basic authentication simultaneously 
- Choose one authentication method - the configuration builder will reject conflicting setups
- Use JWT tokens for modern APIs, Basic auth for legacy systems

### WWW-Authenticate Configuration

The client can automatically handle HTTP authentication challenges (401 responses with WWW-Authenticate headers):

```groovy
// Enable WWW-Authenticate handling with anonymous authentication fallback
def config = HxConfig.builder()
    .withWwwAuthentication(true)
    .build()

def client = HxClient.create(config)

// With custom authentication callback
def config = HxConfig.builder()
    .withWwwAuthentication(true)
    .withWwwAuthenticationCallback({ scheme, realm ->
        if (scheme == AuthenticationScheme.BASIC) {
            // Return base64-encoded credentials for Basic auth
            return Base64.getEncoder().encodeToString("user:pass".getBytes())
        } else if (scheme == AuthenticationScheme.BEARER) {
            // Return bearer token
            return "your-bearer-token"
        }
        return null // Fall back to anonymous auth
    } as AuthenticationCallback)
    .build()

def client = HxClient.create(config)
```

**Supported Authentication Schemes:**
- **Basic**: Supports both credential-based and anonymous authentication
- **Bearer**: Attempts to retrieve anonymous tokens from OAuth2 endpoints when no credentials are provided

**Anonymous Authentication:**
- **Basic**: Uses empty credentials (base64 encoded `:`)
- **Bearer**: Attempts to get anonymous tokens from the authentication endpoint using OAuth2 flow

### Custom Retry Configuration

```groovy
def config = HxConfig.builder()
    .withMaxAttempts(5)
    .withDelay(Duration.ofSeconds(1))
    .withMaxDelay(Duration.ofMinutes(2))
    .withJitter(0.5)
    .withMultiplier(2.0)
    .withRetryStatusCodes([429, 500, 502, 503, 504] as Set)
    .build()
```

### Integration with Existing Retry Configuration

```groovy
// Use any existing Retryable.Config
def retryConfig = Retryable.ofDefaults().config()
def client = HxClient.create(retryConfig)

// Or combine with HTTP-specific settings
def config = HxConfig.builder()
    .withRetryConfig(retryConfig)
    .withJwtToken("your-jwt-token")
    .withRetryStatusCodes([429, 503] as Set)
    .build()
def client = HxClient.create(config)
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
| `basicAuthToken` | Token for HTTP Basic authentication (username:password format) | null |
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
- **`HxTokenManager`**: Thread-safe JWT token lifecycle management
- **`AuthenticationChallenge`**: Represents a parsed WWW-Authenticate challenge
- **`AuthenticationScheme`**: Enumeration of supported authentication schemes (Basic, Bearer)
- **`AuthenticationCallback`**: Interface for providing authentication credentials
- **`WwwAuthenticateParser`**: RFC 7235 compliant parser for WWW-Authenticate headers

## Dependencies

- `lib-retry`: Provides the underlying retry mechanism using Failsafe
- `dev.failsafe:failsafe`: Core retry and circuit breaker library
- `com.google.code.gson:gson`: For JSON parsing in WWW-Authenticate anonymous token retrieval
