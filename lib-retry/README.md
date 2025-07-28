# lib-retry

Retry mechanisms with exponential backoff for handling transient failures.

## Usage

Exponential backoff retry mechanisms with configurable parameters:

```groovy

/*
 * Retryable helper for HTTP operations
 */
class HttpService implements Retryable.Config {
    @Value('${http.retry.delay:1s}')
    Duration retryDelay
    
    @Value('${http.retry.attempts:3}')
    int retryAttempts
    
    @Value('${http.retry.multiplier:1.75}')
    double retryMultiplier
    
    HttpResponse<String> get(URI uri) {
        final retryable = Retryable
                .<HttpResponse<String>>of(this)
                .retryIf((resp) -> resp.statusCode() in [500, 502, 503])
                .onRetry((event) -> log.warn("HTTP retry for $uri - $event"))
        
        return retryable.apply(() -> httpClient.send(uri))
    }
}

/*
 * ExponentialAttempt pattern for async operations
 */
class ServiceConnector {
    @Value('${service.maxAttempts:6}')
    private int maxAttempts

    @Value('${service.retryBackOffBase:3}')
    private int retryBackOffBase

    @Value('${service.retryBackOffDelay:325}')
    private int retryBackOffDelay

    @Value('${service.retryMaxDelay:40s}')
    private Duration retryMaxDelay

    protected ExponentialAttempt newAttempt(int attempt) {
        new ExponentialAttempt()
                .builder()
                .withAttempt(attempt)
                .withMaxDelay(retryMaxDelay)
                .withBackOffBase(retryBackOffBase)
                .withBackOffDelay(retryBackOffDelay)
                .withMaxAttempts(maxAttempts)
                .build()
    }

    CompletableFuture<String> callServiceAsync(String endpoint, int attempt0 = 1) {
        final attempt = newAttempt(attempt0)

        return performRequest(endpoint)
                .exceptionallyCompose { Throwable err ->
                    final retryable = err instanceof IOException || err instanceof TimeoutException
                    if (retryable && attempt.canAttempt()) {
                        final delay = attempt.delay()
                        log.debug("Retrying $endpoint - attempt: ${attempt.current()}, delay: $delay")
                        return CompletableFuture
                                .delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                                .supplyAsync { callServiceAsync(endpoint, attempt.current() + 1) }
                                .thenCompose(Function.identity())
                    }
                    throw err
                }
    }
}
```

## Testing

```bash
./gradlew :lib-retry:test
```
