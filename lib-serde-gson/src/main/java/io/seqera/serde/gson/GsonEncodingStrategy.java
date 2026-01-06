/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.seqera.serde.gson;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapterFactory;
import io.seqera.lang.type.TypeHelper;
import io.seqera.serde.encode.StringEncodingStrategy;
import io.seqera.serde.gson.adapters.DurationAdapter;
import io.seqera.serde.gson.adapters.InstantAdapter;
import io.seqera.serde.gson.adapters.LocalDateAdapter;
import io.seqera.serde.gson.adapters.LocalDateTimeAdapter;
import io.seqera.serde.gson.adapters.LocalTimeAdapter;
import io.seqera.serde.gson.adapters.OffsetDateTimeAdapter;

/**
 * Abstract Gson-based implementation of {@link StringEncodingStrategy} for JSON serialization.
 * <p>
 * This class provides a type-safe way to encode and decode objects to/from JSON strings
 * using Google Gson. The generic type parameter is automatically inferred at runtime
 * using reflection.
 * <p>
 * Features:
 * <ul>
 *   <li>Thread-safe lazy initialization of Gson instance</li>
 *   <li>Fluent configuration API (withPrettyPrint, withSerializeNulls, withTypeAdapterFactory)</li>
 *   <li>Built-in support for Java 8 date/time types (Instant, Duration, LocalDateTime, etc.)</li>
 *   <li>Support for polymorphic serialization via RuntimeTypeAdapterFactory</li>
 * </ul>
 * <p>
 * Usage example:
 * <pre>{@code
 * // Create encoder using anonymous class for type inference
 * GsonEncodingStrategy<MyData> encoder = new GsonEncodingStrategy<MyData>() {};
 *
 * // Encode to JSON
 * String json = encoder.encode(myData);
 *
 * // Decode from JSON
 * MyData decoded = encoder.decode(json);
 *
 * // With configuration
 * GsonEncodingStrategy<MyData> prettyEncoder = new GsonEncodingStrategy<MyData>() {}
 *     .withPrettyPrint(true)
 *     .withSerializeNulls(true);
 * }</pre>
 *
 * @param <V> the type of object to encode/decode
 * @author Paolo Di Tommaso
 */
public abstract class GsonEncodingStrategy<V> implements StringEncodingStrategy<V> {

    private final Type type;
    private volatile Gson gson;
    private boolean prettyPrint = false;
    private boolean serializeNulls = false;
    private TypeAdapterFactory factory;

    /**
     * Creates a new GsonEncodingStrategy with automatic type inference.
     * <p>
     * The generic type parameter is extracted at runtime using reflection.
     * This constructor should be called from an anonymous subclass:
     * <pre>{@code
     * GsonEncodingStrategy<MyType> encoder = new GsonEncodingStrategy<MyType>() {};
     * }</pre>
     */
    protected GsonEncodingStrategy() {
        this.type = TypeHelper.getGenericType(this, 0);
    }

    /**
     * Creates a new GsonEncodingStrategy with an explicit type.
     * <p>
     * Use this constructor when the type cannot be inferred automatically,
     * such as when working with generic types or complex type hierarchies.
     *
     * @param type the type to use for serialization/deserialization
     */
    protected GsonEncodingStrategy(Type type) {
        this.type = type;
    }

    /**
     * Enables or disables pretty printing of JSON output.
     *
     * @param value true to enable pretty printing with indentation
     * @return this encoder for method chaining
     */
    public GsonEncodingStrategy<V> withPrettyPrint(boolean value) {
        this.prettyPrint = value;
        this.gson = null; // Reset to rebuild with new settings
        return this;
    }

    /**
     * Enables or disables serialization of null fields.
     * <p>
     * When enabled, null fields will be included in the JSON output as {@code "field": null}.
     * When disabled (default), null fields are omitted from the output.
     *
     * @param value true to include null fields in output
     * @return this encoder for method chaining
     */
    public GsonEncodingStrategy<V> withSerializeNulls(boolean value) {
        this.serializeNulls = value;
        this.gson = null; // Reset to rebuild with new settings
        return this;
    }

    /**
     * Sets a custom TypeAdapterFactory for handling specific types.
     * <p>
     * This is commonly used with {@link RuntimeTypeAdapterFactory} for polymorphic
     * serialization of class hierarchies.
     *
     * @param factory the TypeAdapterFactory to register
     * @return this encoder for method chaining
     */
    public GsonEncodingStrategy<V> withTypeAdapterFactory(TypeAdapterFactory factory) {
        this.factory = factory;
        this.gson = null; // Reset to rebuild with new settings
        return this;
    }

    /**
     * Gets the Gson instance, creating it lazily with thread-safe double-checked locking.
     *
     * @return the configured Gson instance
     */
    private Gson gson0() {
        Gson result = gson;
        if (result == null) {
            synchronized (this) {
                result = gson;
                if (result == null) {
                    gson = result = createGson();
                }
            }
        }
        return result;
    }

    /**
     * Creates a new Gson instance with the current configuration.
     *
     * @return a configured Gson instance
     */
    private Gson createGson() {
        GsonBuilder builder = new GsonBuilder();

        // Register Java 8 date/time type adapters
        builder.registerTypeAdapter(Instant.class, new InstantAdapter());
        builder.registerTypeAdapter(Duration.class, new DurationAdapter());
        builder.registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeAdapter());
        builder.registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter());
        builder.registerTypeAdapter(LocalDate.class, new LocalDateAdapter());
        builder.registerTypeAdapter(LocalTime.class, new LocalTimeAdapter());

        // Apply configuration options
        if (factory != null) {
            builder.registerTypeAdapterFactory(factory);
        }
        if (prettyPrint) {
            builder.setPrettyPrinting();
        }
        if (serializeNulls) {
            builder.serializeNulls();
        }

        return builder.create();
    }

    /**
     * Encodes the given object to a JSON string.
     *
     * @param value the object to encode, may be null
     * @return the JSON string representation, or null if the input is null
     * @throws RuntimeException if encoding fails
     */
    @Override
    public String encode(V value) {
        if (value == null) {
            return null;
        }
        try {
            return gson0().toJson(value, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a JSON string to an object of the target type.
     *
     * @param value the JSON string to decode, may be null
     * @return the decoded object, or null if the input is null
     * @throws RuntimeException if decoding fails due to invalid JSON or type mismatch
     */
    @Override
    public V decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return gson0().fromJson(value, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JSON: " + value, e);
        }
    }
}
