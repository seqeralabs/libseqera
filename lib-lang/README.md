# lib-lang

Language and type utilities with reflection helpers for dynamic operations.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-lang:1.0.0'
}
```

## Usage

### Superclass Type Resolution

Runtime generic type resolution from superclass using `getGenericType`:

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

### Interface Type Resolution

Resolve generic type arguments from implemented interfaces using `getInterfaceTypeArguments`:

```groovy
import io.seqera.lang.type.TypeHelper

interface Handler<P, R> {
    R handle(P params)
}

class StringToIntHandler implements Handler<String, Integer> {
    Integer handle(String params) { params.length() }
}

// Get the type arguments for the Handler interface
Type[] types = TypeHelper.getInterfaceTypeArguments(StringToIntHandler, Handler)
// types[0] = String.class (params type)
// types[1] = Integer.class (result type)
```

### Raw Type Extraction

Extract the raw `Class` from a `Type`, handling both `Class` and `ParameterizedType`:

```groovy
import io.seqera.lang.type.TypeHelper

Type[] types = TypeHelper.getInterfaceTypeArguments(MyHandler, Handler)
Class<?> paramsClass = TypeHelper.getRawType(types[0])  // Works with Class or ParameterizedType
```

## Testing

```bash
./gradlew :lib-lang:test
```
