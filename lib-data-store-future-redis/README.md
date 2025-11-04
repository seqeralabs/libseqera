# lib-data-store-future-redis

Distributed CompletableFuture store with Redis and local implementations for handling asynchronous operations across service instances.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-store-future-redis:1.0.0'
}
```

## Usage

Distributed future handling for cross-service asynchronous operations:

```groovy
// Create a custom future store
@Singleton
class BuildResultStore extends AbstractFutureStore<BuildResult> {

    BuildResultStore(FutureHash<String> store) {
        super(store, new BuildResultEncoder())
    }

    @Override
    String prefix() {
        return "build-results:v1:"
    }

    @Override
    Duration getTimeout() {
        return Duration.ofMinutes(10)
    }

    @Override
    Duration getPollInterval() {
        return Duration.ofMillis(500)
    }
}

// Service A: Create a future and wait for result
@Inject
BuildResultStore buildStore

def buildId = "build-123"

// Create future (non-blocking)
CompletableFuture<BuildResult> future = buildStore.create(buildId)

// Register callback
future.thenAccept { result ->
    log.info("Build completed: ${result.status}")
    // Process result...
}

// Service B: Complete the future from another instance
@Inject
BuildResultStore buildStore

def buildId = "build-123"
def result = new BuildResult(
    status: "success",
    imageId: "sha256:abc123",
    buildTime: Duration.ofMinutes(5)
)

// Complete the future (any service instance can do this)
buildStore.complete(buildId, result)
```

## Custom Encoding

Implement encoding strategy for custom types:

```groovy
class BuildResultEncoder implements StringEncodingStrategy<BuildResult> {

    @Override
    String encode(BuildResult value) {
        return JsonOutput.toJson(value)
    }

    @Override
    BuildResult decode(String encoded) {
        return new JsonSlurper().parseText(encoded) as BuildResult
    }
}
```

## Key Features

- **Distributed Futures**: CompletableFuture that can be completed from any service instance
- **Timeout Support**: Configurable timeouts for future completion
- **Poll Intervals**: Adjustable polling frequency for checking results
- **Type Safety**: Generic type support with custom encoding strategies
- **Automatic Cleanup**: Expired futures are automatically cleaned up by Redis

## Testing

```bash
./gradlew :lib-data-store-future-redis:test
```
