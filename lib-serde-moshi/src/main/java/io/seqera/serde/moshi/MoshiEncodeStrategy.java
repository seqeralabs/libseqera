/*
 * Copyright 2025, Seqera Labs
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

package io.seqera.serde.moshi;

import java.lang.reflect.Type;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import io.seqera.lang.type.TypeHelper;
import io.seqera.serde.encode.StringEncodingStrategy;

/**
 * Implements a JSON {@link StringEncodingStrategy} based on Moshi JSON serializer.
 *
 * <p>This class provides a convenient base for creating encoding strategies that use
 * Moshi for JSON serialization and deserialization. It automatically configures standard
 * adapters for common types including byte arrays, date/time objects, paths, and URIs.</p>
 *
 * <p>The class uses Java reflection to determine the type parameter at runtime, allowing
 * for type-safe encoding and decoding operations without explicit type specification in
 * most cases.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Create an encoder for a specific type
 * MoshiEncodeStrategy<MyClass> encoder = new MoshiEncodeStrategy<MyClass>() {};
 *
 * // Encode an object to JSON
 * String json = encoder.encode(myObject);
 *
 * // Decode JSON back to an object
 * MyClass decoded = encoder.decode(json);
 * }</pre>
 *
 * @param <V> the type of value to encode/decode
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @see <a href="https://github.com/square/moshi">Moshi GitHub Repository</a>
 * @see <a href="https://www.baeldung.com/java-json-moshi">Moshi Tutorial on Baeldung</a>
 */
public abstract class MoshiEncodeStrategy<V> implements StringEncodingStrategy<V> {

    private final Type type;
    private final Moshi moshi;
    private final JsonAdapter<V> jsonAdapter;

    /**
     * Constructs a MoshiEncodeStrategy using type inference from the generic parameter.
     *
     * <p>This constructor automatically determines the type parameter using reflection
     * and initializes the strategy with standard adapters.</p>
     */
    protected MoshiEncodeStrategy() {
        this.type = TypeHelper.getGenericType(this, 0);
        this.moshi = buildMoshi(null);
        this.jsonAdapter = moshi.adapter(type);
    }

    /**
     * Constructs a MoshiEncodeStrategy with a custom adapter factory.
     *
     * <p>This constructor allows adding custom JSON adapters in addition to the
     * standard adapters provided by default.</p>
     *
     * @param customFactory a custom JsonAdapter.Factory to add to the Moshi instance
     */
    protected MoshiEncodeStrategy(JsonAdapter.Factory customFactory) {
        this.type = TypeHelper.getGenericType(this, 0);
        this.moshi = buildMoshi(customFactory);
        this.jsonAdapter = moshi.adapter(type);
    }

    /**
     * Constructs a MoshiEncodeStrategy with an explicit type.
     *
     * <p>This constructor is useful when type inference is not possible or when
     * working with complex generic types.</p>
     *
     * @param type the Type to use for encoding/decoding
     */
    protected MoshiEncodeStrategy(Type type) {
        this.type = type;
        this.moshi = buildMoshi(null);
        this.jsonAdapter = moshi.adapter(type);
    }

    /**
     * Builds a Moshi instance with standard and optional custom adapters.
     *
     * @param customFactory an optional custom JsonAdapter.Factory to add
     * @return the configured Moshi instance
     */
    private Moshi buildMoshi(JsonAdapter.Factory customFactory) {
        final Moshi.Builder builder = new Moshi.Builder()
                .add(new ByteArrayAdapter())
                .add(new DateTimeAdapter())
                .add(new PathAdapter())
                .add(new UriAdapter());
        // add custom factory if provided
        if (customFactory != null) {
            builder.add(customFactory);
        }
        return builder.build();
    }

    /**
     * Encodes a value to its JSON string representation.
     *
     * @param value the value to encode; may be null
     * @return the JSON string representation, or null if value is null
     */
    @Override
    public String encode(V value) {
        if (value == null) {
            return null;
        }
        return jsonAdapter.toJson(value);
    }

    /**
     * Decodes a JSON string to the target type.
     *
     * @param value the JSON string to decode; may be null
     * @return the decoded value, or null if value is null
     */
    @Override
    public V decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonAdapter.fromJson(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JSON: " + value, e);
        }
    }
}
