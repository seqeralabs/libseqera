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

import java.net.URI;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

/**
 * Moshi adapter for {@link URI} serialization.
 *
 * <p>This adapter handles the conversion between URI objects and their string
 * representation in JSON format.</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class UriAdapter {

    /**
     * Serializes a URI to its string representation.
     *
     * @param uri the URI to serialize
     * @return the string representation of the URI, or null if uri is null
     */
    @ToJson
    public String serialize(URI uri) {
        return uri != null ? uri.toString() : null;
    }

    /**
     * Deserializes a string to a URI object.
     *
     * @param data the string representation of the URI
     * @return the URI object, or null if data is null
     */
    @FromJson
    public URI deserialize(String data) {
        return data != null ? URI.create(data) : null;
    }
}
