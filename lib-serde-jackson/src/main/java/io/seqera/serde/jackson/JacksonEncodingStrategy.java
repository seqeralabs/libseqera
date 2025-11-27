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

package io.seqera.serde.jackson;

import java.lang.reflect.Type;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.seqera.lang.type.TypeHelper;
import io.seqera.serde.encode.StringEncodingStrategy;

/**
 * Implements a JSON StringEncodingStrategy based on Jackson JSON serializer.
 *
 * <p>This class provides a convenient base for creating encoding strategies that use
 * Jackson for JSON serialization and deserialization. It automatically configures
 * standard modules for common types including Java 8 date/time objects.</p>
 *
 * <p>The class uses Java reflection to determine the type parameter at runtime, allowing
 * for type-safe encoding and decoding operations without explicit type specification
 * in most cases.</p>
 *
 * @param <V> the type of objects to be encoded/decoded
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public abstract class JacksonEncodingStrategy<V> implements StringEncodingStrategy<V> {

    private final Type type;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a JacksonEncodingStrategy using type inference from the generic parameter.
     * Automatically determines the type parameter using reflection and initializes
     * the strategy with standard configuration.
     */
    protected JacksonEncodingStrategy() {
        this.type = TypeHelper.getGenericType(this, 0);
        this.objectMapper = buildObjectMapper(null);
    }

    /**
     * Constructs a JacksonEncodingStrategy with a pre-configured ObjectMapper.
     * Allows using a custom ObjectMapper with specific configuration.
     *
     * @param objectMapper a pre-configured ObjectMapper instance; if null, a default one is created
     */
    protected JacksonEncodingStrategy(ObjectMapper objectMapper) {
        this.type = TypeHelper.getGenericType(this, 0);
        this.objectMapper = objectMapper != null ? configureObjectMapper(objectMapper) : buildObjectMapper(null);
    }

    /**
     * Constructs a JacksonEncodingStrategy with an explicit type.
     * Useful when type inference is not possible or with complex generic types.
     *
     * @param type the Type to use for encoding/decoding
     */
    protected JacksonEncodingStrategy(Type type) {
        this.type = type;
        this.objectMapper = buildObjectMapper(null);
    }

    /**
     * Builds an ObjectMapper with standard configuration.
     *
     * @param base an optional base ObjectMapper to copy; if null, a new one is created
     * @return the configured ObjectMapper instance
     */
    private ObjectMapper buildObjectMapper(ObjectMapper base) {
        final ObjectMapper mapper = base != null ? base.copy() : new ObjectMapper();
        return configureObjectMapper(mapper);
    }

    /**
     * Configures an ObjectMapper with standard settings.
     *
     * @param mapper the ObjectMapper to configure
     * @return the configured ObjectMapper
     */
    private ObjectMapper configureObjectMapper(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
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
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode JSON: " + e.getMessage(), e);
        }
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
            return objectMapper.readValue(value, objectMapper.constructType(type));
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JSON: " + value, e);
        }
    }
}
