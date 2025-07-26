# lib-pool

Object pooling utilities for efficient resource management and reduced allocation overhead.

## Usage

Thread-safe object pooling for acquiring expensive-to-create resources:

```groovy
import io.seqera.util.pool.SimplePool

// Create pool with factory method
static private SimplePool<ExpensiveResource> resourcePool = new SimplePool<>(()-> createResource())

// Automatic resource management with apply()
static String processData(Object data) {
    return resourcePool.apply((resource) -> resource.process(data))
}

// Manual resource management with borrow/release
static Result performOperation(InputData input) {
    final resource = resourcePool.borrow()
    try {
        return resource.execute(input)
    } finally {
        resourcePool.release(resource)
    }
}
```

## Testing

```bash
./gradlew :lib-pool:test
```