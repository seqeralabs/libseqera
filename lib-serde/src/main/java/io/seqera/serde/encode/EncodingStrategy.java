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

package io.seqera.serde.encode;

/**
 * A generic interface for encoding and decoding objects between two types.
 *
 * <p>This interface defines the contract for serialization and deserialization strategies,
 * allowing for pluggable encoding implementations such as JSON, XML, binary formats, or
 * custom encodings. Implementations should be stateless and thread-safe.</p>
 *
 * <p>Common use cases include:</p>
 * <ul>
 *   <li>Message serialization for queues and streams</li>
 *   <li>Data persistence and caching</li>
 *   <li>Network communication protocols</li>
 *   <li>Configuration and data exchange formats</li>
 * </ul>
 *
 * <p>Example implementation for JSON encoding:</p>
 * <pre>{@code
 * class JsonEncodingStrategy implements EncodingStrategy<Object, String> {
 *     @Override
 *     String encode(Object object) {
 *         return JsonOutput.toJson(object)
 *     }
 *
 *     @Override
 *     Object decode(String encoded) {
 *         return new JsonSlurper().parseText(encoded)
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of the original object to be encoded
 * @param <S> the type of the encoded representation (e.g., String, byte[], etc.)
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
public interface EncodingStrategy<T,S> {

    /**
     * Encodes an object of type {@code T} into its corresponding encoded representation of type {@code S}.
     *
     * <p>This method should be deterministic and produce the same encoded output for equivalent
     * input objects. Implementations must handle null inputs appropriately, either by returning
     * a null representation or throwing an appropriate exception.</p>
     *
     * @param object the object to encode; may be null depending on implementation
     * @return the encoded representation of the object
     */
    S encode(T object);

    /**
     * Decodes an encoded representation of type {@code S} back into its original form of type {@code T}.
     *
     * <p>This method should be the inverse of {@link #encode(Object)}, such that for any object
     * {@code obj}, the expression {@code decode(encode(obj))} should yield an equivalent object.
     * Implementations must handle malformed or corrupted encoded data gracefully.</p>
     *
     * @param encoded the encoded representation to decode; may be null depending on implementation
     * @return the decoded object
     */
    T decode(S encoded);

}
