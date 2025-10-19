# lib-serde-moshi

Moshi-based JSON serialization library with pre-configured adapters for common Java types.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-serde-moshi:1.0.0'
}
```

## Usage

Type-safe JSON encoding using Moshi with automatic adapter configuration:

```java
import io.seqera.serde.moshi.MoshiEncodeStrategy;

// Basic usage with type inference
public class MyData {
    String name;
    Instant timestamp;
    Duration duration;
}

MoshiEncodeStrategy<MyData> encoder = new MoshiEncodeStrategy<MyData>() {};

// Encode to JSON
MyData data = new MyData("example", Instant.now(), Duration.ofHours(2));
String json = encoder.encode(data);

// Decode from JSON
MyData decoded = encoder.decode(json);
```

### Built-in Type Adapters

The library includes pre-configured adapters for common Java types:

```java
import java.time.Instant;
import java.time.Duration;
import java.nio.file.Path;
import java.net.URI;

// Automatically handles:
// - Instant (ISO-8601 format)
// - Duration (nanosecond precision)
// - Path (string representation)
// - URI (string representation)
// - byte[] (Base64 encoding)

public class ComplexData {
    Instant createdAt;        // Serialized as "2025-10-20T00:00:00Z"
    Duration timeout;         // Serialized as nanoseconds
    Path filePath;           // Serialized as string
    URI endpoint;            // Serialized as string
    byte[] payload;          // Serialized as Base64
}

MoshiEncodeStrategy<ComplexData> encoder = new MoshiEncodeStrategy<ComplexData>() {};
String json = encoder.encode(complexData);
```

### Custom Adapters

Add custom JSON adapters for specialized types:

```java
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

// Define custom adapter
public class MyCustomAdapter extends JsonAdapter.Factory {
    @ToJson
    String toJson(CustomType value) {
        return value.toString();
    }

    @FromJson
    CustomType fromJson(String json) {
        return CustomType.parse(json);
    }
}

// Use with MoshiEncodeStrategy
MoshiEncodeStrategy<MyData> encoder = new MoshiEncodeStrategy<MyData>(new MyCustomAdapter()) {};
```

### Marker Interface

Use `MoshiSerializable` to mark classes intended for Moshi serialization:

```java
import io.seqera.serde.moshi.MoshiSerializable;

public class SerializableData implements MoshiSerializable {
    private String id;
    private Map<String, Object> attributes;

    // getters/setters
}
```

## Features

- **Type-safe encoding**: Automatic type inference using Java generics
- **Pre-configured adapters**: Built-in support for common Java types
- **Extensible**: Easy custom adapter integration
- **StringEncodingStrategy**: Implements lib-serde encoding interface
- **ISO-8601 dates**: Standard format for Instant serialization
- **Backward compatibility**: Duration supports legacy float format

## Testing

```bash
./gradlew :lib-serde-moshi:test
```

## Dependencies

- `lib-serde` - Base serialization interfaces
- `lib-lang` - Type utilities
- `moshi:1.15.2` - Moshi JSON library
- `moshi-adapters:1.15.2` - Standard Moshi adapters
