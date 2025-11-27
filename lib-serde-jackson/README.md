# lib-serde-jackson

Jackson-based JSON serialization library implementing the lib-serde `StringEncodingStrategy` interface.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-serde-jackson:1.0.0'
}
```

## Usage

Type-safe JSON encoding using Jackson with automatic Java 8 date/time support:

```java
import io.seqera.serde.jackson.JacksonEncodingStrategy;

// Basic usage with type inference
public class MyData {
    String name;
    Instant timestamp;
    Duration duration;
}

JacksonEncodingStrategy<MyData> encoder = new JacksonEncodingStrategy<MyData>() {};

// Encode to JSON
MyData data = new MyData();
data.name = "example";
data.timestamp = Instant.now();
data.duration = Duration.ofHours(2);
String json = encoder.encode(data);

// Decode from JSON
MyData decoded = encoder.decode(json);
```

### Using with Custom ObjectMapper

Provide a pre-configured ObjectMapper for custom serialization settings:

```java
import com.fasterxml.jackson.databind.ObjectMapper;

ObjectMapper customMapper = new ObjectMapper();
// Configure your custom settings...

JacksonEncodingStrategy<MyData> encoder = new JacksonEncodingStrategy<MyData>(customMapper) {};
```

### Built-in Type Support

The library automatically configures Jackson's `JavaTimeModule` for Java 8 date/time types:

```java
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;

// Automatically handles:
// - Instant (ISO-8601 format)
// - Duration (ISO-8601 format)
// - LocalDateTime, LocalDate, LocalTime
// - ZonedDateTime, OffsetDateTime

public class TimeData {
    Instant createdAt;        // Serialized as "2025-10-20T00:00:00Z"
    Duration timeout;         // Serialized as "PT2H"
    LocalDateTime localTime;  // Serialized as "2025-10-20T12:30:00"
}

JacksonEncodingStrategy<TimeData> encoder = new JacksonEncodingStrategy<TimeData>() {};
String json = encoder.encode(timeData);
```

### Integration with Tiered Cache

Use with `lib-cache-tiered-redis` for cache entry serialization:

```java
import io.seqera.cache.tiered.AbstractTieredCache;

@Singleton
public class UserCache extends AbstractTieredCache<String, User> {

    public UserCache(L2TieredCache l2Cache, ObjectMapper objectMapper) {
        super(l2Cache, new JacksonEncodingStrategy<Entry>(objectMapper) {});
    }

    // ... cache configuration methods
}
```

## Features

- **Type-safe encoding**: Automatic type inference using Java generics
- **Java 8 date/time support**: Pre-configured `JavaTimeModule` for modern date/time types
- **ISO-8601 dates**: Standard format for date/time serialization (not timestamps)
- **StringEncodingStrategy**: Implements lib-serde encoding interface
- **Custom ObjectMapper**: Accept pre-configured ObjectMapper for flexibility
- **Null-safe**: Handles null values gracefully

## Testing

```bash
./gradlew :lib-serde-jackson:test
```

## Dependencies

- `lib-serde` - Base serialization interfaces
- `lib-lang` - Type utilities
- `jackson-databind` - Jackson JSON library
- `jackson-datatype-jsr310` - Java 8 date/time support
