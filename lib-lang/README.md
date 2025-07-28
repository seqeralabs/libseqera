# lib-lang

Language and type utilities with reflection helpers for dynamic operations.

## Usage

Runtime generic type resolution using `getGenericType`:

```groovy
import io.seqera.lang.type.TypeHelper

// Generic base class that needs to know its type parameter at runtime
abstract class GenericProcessor<T> {
    
    protected void processData() {
        final type = TypeHelper.getGenericType(this, 0)  // Get the first generic type parameter
        log.debug "Processing data of type: ${type.simpleName}"
        // Use the resolved type for operations...
    }
}

// Concrete implementations
class StringProcessor extends GenericProcessor<String> {
    // TypeHelper.getGenericType(this, 0) returns String.class at runtime
}

class DataProcessor extends GenericProcessor<CustomData> {
    // TypeHelper.getGenericType(this, 0) returns CustomData.class at runtime
}
```

## Testing

```bash
./gradlew :lib-lang:test
```
