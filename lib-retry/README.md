# lib-retry

Retry mechanisms with exponential backoff for handling transient failures. Built on top of the [Failsafe](https://failsafe.dev/) library.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-retry:2.0.0'
}
```

## Features

- Exponential backoff with configurable delay, max delay, and multiplier
- Jitter support to prevent thundering herd
- Conditional retry based on exception types or result predicates
- Synchronous and asynchronous execution
- Event listeners for retry attempts and failures
- Automatic HttpResponse cleanup to prevent resource leaks

## Default Configuration

- **Initial delay**: 500ms
- **Max delay**: 30s
- **Max attempts**: 5
- **Jitter**: 0.25 (25%)
- **Multiplier**: 2.0 (exponential backoff)
- **Default retry condition**: IOException and its subclasses

## Usage

### Basic Retryable with Custom Configuration

```java
/*
 * Retryable helper for HTTP operations
 */
class HttpService implements Retryable.Config {
    @Value('${http.retry.delay:1s}')
    private Duration delay;

    @Value('${http.retry.maxDelay:30s}')
    private Duration maxDelay;

    @Value('${http.retry.attempts:3}')
    private int maxAttempts;

    @Value('${http.retry.jitter:0.25}')
    private double jitter;

    @Value('${http.retry.multiplier:2.0}')
    private double multiplier;

    @Override
    public Duration getDelay() { return delay; }

    @Override
    public Duration getMaxDelay() { return maxDelay; }

    @Override
    public int getMaxAttempts() { return maxAttempts; }

    @Override
    public double getJitter() { return jitter; }

    @Override
    public double getMultiplier() { return multiplier; }

    public HttpResponse<String> get(URI uri) {
        final Retryable<HttpResponse<String>> retryable = Retryable
                .<HttpResponse<String>>of(this)
                .retryIf((resp) -> resp.statusCode() >= 500 && resp.statusCode() < 600)
                .onRetry((event) -> log.warn("HTTP retry for {} - {}", uri, event));

        return retryable.apply(() -> httpClient.send(uri, HttpResponse.BodyHandlers.ofString()));
    }
}
```

### Using Default Configuration

```java
// Simple retry with defaults (IOException only)
String result = Retryable.<String>ofDefaults()
    .apply(() -> performOperation());

// Retry with custom exception condition
String result = Retryable.<String>ofDefaults()
    .retryCondition(e -> e instanceof IOException || e instanceof TimeoutException)
    .onRetry(event -> log.warn("Retrying: {}", event))
    .apply(() -> performOperation());
```

### Async Execution with Executor

```java
Executor executor = Executors.newCachedThreadPool();

CompletableFuture<String> result = Retryable.<String>ofDefaults()
    .retryCondition(e -> e instanceof IOException)
    .onRetry(event -> log.warn("Async retry: {}", event))
    .applyAsync(() -> performAsyncOperation(), executor);
```

### ExponentialAttempt for Manual Control

For cases where you need manual control over retry logic:

```java
class ServiceConnector {
    @Value('${service.maxAttempts:6}')
    private int maxAttempts;

    @Value('${service.retryBackOffBase:3}')
    private int retryBackOffBase;

    @Value('${service.retryBackOffDelay:325}')
    private int retryBackOffDelay;

    @Value('${service.retryMaxDelay:40s}')
    private Duration retryMaxDelay;

    protected ExponentialAttempt newAttempt(int attempt) {
        return new ExponentialAttempt()
                .withAttempt(attempt)
                .withMaxDelay(retryMaxDelay)
                .withBackOffBase(retryBackOffBase)
                .withBackOffDelay(retryBackOffDelay)
                .withMaxAttempts(maxAttempts);
    }

    public CompletableFuture<String> callServiceAsync(String endpoint, int attempt0) {
        final ExponentialAttempt attempt = newAttempt(attempt0);

        return performRequest(endpoint)
                .exceptionallyCompose(err -> {
                    final boolean retryable = err instanceof IOException || err instanceof TimeoutException;
                    if (retryable && attempt.canAttempt()) {
                        final Duration delay = attempt.next();
                        log.debug("Retrying {} - attempt: {}, delay: {}", endpoint, attempt.current(), delay);
                        return CompletableFuture
                                .delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                                .supplyAsync(() -> callServiceAsync(endpoint, attempt.current()))
                                .thenCompose(Function.identity());
                    }
                    throw sneakyThrow(err);
                });
    }
}
```

## Event Handling

The `Retryable.Event` class provides information about retry attempts:

```java
Retryable.<String>ofDefaults()
    .onRetry(event -> {
        log.warn("Event: {}, Attempt: {}, Failure: {}",
            event.getEvent(),      // "Retry" or "Failure"
            event.getAttempt(),    // Attempt number (1-based)
            event.getFailure()     // Exception if failed
        );
    })
    .apply(() -> performOperation());
```

## Testing

```bash
./gradlew :lib-retry:test
```
