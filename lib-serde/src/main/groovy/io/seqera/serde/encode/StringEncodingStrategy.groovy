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

package io.seqera.serde.encode

/**
 * A specialized encoding strategy for string-based serialization formats.
 * 
 * <p>This interface extends {@link EncodingStrategy} to specifically handle encoding and decoding
 * operations where the encoded representation is a {@link String}. This is commonly used for
 * text-based formats such as JSON, XML, YAML, CSV, or custom string representations.</p>
 * 
 * <p>String-based encoding is particularly useful for:</p>
 * <ul>
 *   <li>Human-readable data formats</li>
 *   <li>Web APIs and REST services</li>
 *   <li>Configuration files and settings</li>
 *   <li>Logging and debugging output</li>
 *   <li>Text-based message protocols</li>
 * </ul>
 * 
 * <p>Implementations should ensure that the encoded strings are valid according to their
 * format specification and handle character encoding consistently (typically UTF-8).</p>
 * 
 * <p>Example implementation for JSON encoding:</p>
 * <pre>{@code
 * class JsonStringEncoder implements StringEncodingStrategy<Object> {
 *     @Override
 *     String encode(Object value) {
 *         return new ObjectMapper().writeValueAsString(value)
 *     }
 *     
 *     @Override
 *     Object decode(String value) {
 *         return new ObjectMapper().readValue(value, Object.class)
 *     }
 * }
 * }</pre>
 *
 * @param <V> the type of the value to be encoded/decoded
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see EncodingStrategy
 */
interface StringEncodingStrategy<V> extends EncodingStrategy<V,String> {

    /**
     * Encodes a value into its string representation.
     * 
     * <p>The resulting string should be a valid representation according to the format
     * specification implemented by this strategy. The encoding should be deterministic
     * and produce consistent output for equivalent input values.</p>
     * 
     * <p>Implementations should consider:</p>
     * <ul>
     *   <li>Character encoding consistency (UTF-8 recommended)</li>
     *   <li>Proper escaping of special characters</li>
     *   <li>Handling of null values and empty objects</li>
     *   <li>Format-specific requirements (e.g. JSON schema compliance)</li>
     * </ul>
     *
     * @param value the value to encode; may be null depending on implementation
     * @return the string representation of the value
     */
    @Override
    String encode(V value)

    /**
     * Decodes a string representation back into the original value type.
     * 
     * <p>This method should be able to reconstruct the original value from its string
     * representation as produced by {@link #encode(Object)}. The implementation must
     * validate the input string format and handle malformed data appropriately.</p>
     * 
     * <p>Implementations should consider:</p>
     * <ul>
     *   <li>Format validation and error reporting</li>
     *   <li>Proper handling of character encoding</li>
     *   <li>Recovery from partially corrupted data when possible</li>
     *   <li>Type safety and validation of decoded objects</li>
     * </ul>
     *
     * @param value the string representation to decode; may be null depending on implementation
     * @return the decoded value
     */
    @Override
    V decode(String value)

}
