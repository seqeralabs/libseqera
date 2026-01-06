# lib-serde-gson

Gson-based JSON serialization library implementing the lib-serde `StringEncodingStrategy` interface.

## Installation

```gradle
dependencies {
    implementation 'io.seqera:lib-serde-gson:1.0.0'
}
```

## Usage

### Basic Usage

```java
// Create an encoder for your type using anonymous class
GsonEncodingStrategy<MyData> encoder = new GsonEncodingStrategy<MyData>() {};

// Encode to JSON
String json = encoder.encode(myData);

// Decode from JSON
MyData decoded = encoder.decode(json);
```

### Configuration Options

```java
GsonEncodingStrategy<MyData> encoder = new GsonEncodingStrategy<MyData>() {}
    .withPrettyPrint(true)       // Enable pretty printing
    .withSerializeNulls(true);   // Include null fields in output
```

### Polymorphic Serialization

Use `RuntimeTypeAdapterFactory` for serializing class hierarchies:

```java
RuntimeTypeAdapterFactory<Animal> factory = RuntimeTypeAdapterFactory
    .of(Animal.class, "@type")
    .registerSubtype(Dog.class, "Dog")
    .registerSubtype(Cat.class, "Cat");

GsonEncodingStrategy<Animal> encoder = new GsonEncodingStrategy<Animal>() {}
    .withTypeAdapterFactory(factory);

// Serializes to: {"@type":"Dog","name":"Rex","barkVolume":10}
String json = encoder.encode(new Dog("Rex", 10));

// Deserializes to correct subtype
Animal animal = encoder.decode(json); // Returns Dog instance
```

## Features

- **Type-safe encoding** with automatic generic type inference
- **Fluent configuration API** for customizing serialization behavior
- **Thread-safe** lazy Gson initialization with double-checked locking
- **Java 8 date/time support** with ISO-8601 format:
  - `Instant` - e.g., `"2025-01-01T00:00:00Z"`
  - `Duration` - e.g., `"PT1H30M"`
  - `OffsetDateTime` - e.g., `"2025-01-01T12:00:00+02:00"`
  - `LocalDateTime` - e.g., `"2025-01-01T12:00:00"`
  - `LocalDate` - e.g., `"2025-01-01"`
  - `LocalTime` - e.g., `"12:30:45"`
- **Polymorphic type handling** via RuntimeTypeAdapterFactory
- **Null-safe** encode/decode operations

## Type Adapters

The following type adapters are automatically registered:

| Type | Format | Example |
|------|--------|---------|
| `Instant` | ISO-8601 | `"2025-01-06T10:30:00Z"` |
| `Duration` | ISO-8601 | `"PT2H30M"` |
| `OffsetDateTime` | ISO-8601 | `"2025-01-06T10:30:00+02:00"` |
| `LocalDateTime` | ISO-8601 | `"2025-01-06T10:30:00"` |
| `LocalDate` | ISO-8601 | `"2025-01-06"` |
| `LocalTime` | ISO-8601 | `"10:30:45"` |

### Custom Type Adapters

You can create custom type adapters for your own types by extending `TypeAdapter<T>`:

```java
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

public class MoneyAdapter extends TypeAdapter<Money> {
    @Override
    public void write(JsonWriter writer, Money value) throws IOException {
        if (value == null) {
            writer.nullValue();
            return;
        }
        // Serialize as "100.50 USD"
        writer.value(value.getAmount() + " " + value.getCurrency());
    }

    @Override
    public Money read(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        String[] parts = reader.nextString().split(" ");
        return new Money(new BigDecimal(parts[0]), parts[1]);
    }
}
```

Register the custom adapter using a `TypeAdapterFactory`:

```java
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

TypeAdapterFactory moneyFactory = new TypeAdapterFactory() {
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() == Money.class) {
            return (TypeAdapter<T>) new MoneyAdapter();
        }
        return null;
    }
};

GsonEncodingStrategy<Order> encoder = new GsonEncodingStrategy<Order>() {}
    .withTypeAdapterFactory(moneyFactory);
```

## Dependencies

- `lib-serde` - Base serialization interfaces
- `lib-lang` - Type utilities for generic type inference
- `com.google.code.gson:gson` - Google Gson library
