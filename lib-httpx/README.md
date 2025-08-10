# lib-httpx

Enhanced HTTP client extension for Java `HttpClient` with built-in retry logic and JWT token refresh capabilities.

## Features

- **Retry Logic**: Automatic retry for configurable HTTP status codes (default: 429, 500, 502, 503, 504)
- **JWT Token Refresh**: Automatic JWT token refresh when receiving 401 Unauthorized responses
- **Configurable**: Customizable retry policies, timeouts, and token refresh settings
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

### JWT Token Configuration

```groovy
def config = HxConfig.builder()
    .withJwtToken("your-jwt-token")
    .withRefreshToken("your-refresh-token")
    .withRefreshTokenUrl("https://api.example.com/oauth/token")
    .build()

def client = HxClient.create(config)
```

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
| `tokenRefreshTimeout` | Timeout for token refresh requests | 30s |

## API Documentation

All classes and methods include comprehensive Javadoc documentation covering:

- **Class-level documentation**: Complete overview of functionality and usage patterns
- **Method documentation**: Detailed parameter descriptions, return values, and behavior
- **Usage examples**: Code samples showing common use cases
- **Thread safety notes**: Concurrency guarantees and synchronization details
- **Error handling**: Exception types and retry behavior

### Key Classes

- **`HxClient`** (Http eXtended Client): Main client class with retry and JWT functionality
- **`HxConfig`**: Configuration builder with all available options including Retryable.Config integration
- **`HxTokenManager`**: Thread-safe JWT token lifecycle management

## Dependencies

- `lib-retry`: Provides the underlying retry mechanism using Failsafe
- `groovy-json`: For JSON processing during token refresh
- `dev.failsafe:failsafe`: Core retry and circuit breaker library
