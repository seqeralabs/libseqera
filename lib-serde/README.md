# lib-serde

Serialization and deserialization utilities with pluggable encoding strategies.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-serde:1.0.0'
}
```

## Usage

Flexible encoding strategies for data transformation and messaging:

```groovy
import io.seqera.serde.encode.StringEncodingStrategy

// JSON encoding strategy
def jsonStrategy = new StringEncodingStrategy() {
    @Override
    String encode(Object obj) {
        return JsonOutput.toJson(obj)
    }
    
    @Override
    <T> T decode(String data, Class<T> type) {
        return new JsonSlurper().parseText(data) as T
    }
}

```

## Testing

```bash
./gradlew :lib-serde:test
```
