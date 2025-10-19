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

import java.util.Base64;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

/**
 * Moshi adapter for byte array JSON serialization using Base64 encoding.
 *
 * <p>This adapter handles the conversion between byte arrays and their Base64 string
 * representation in JSON format. It uses the standard Java Base64 encoder/decoder
 * for reliable and efficient encoding.</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ByteArrayAdapter {

    /**
     * Serializes a byte array to a Base64-encoded string for JSON representation.
     *
     * @param data the byte array to serialize
     * @return the Base64-encoded string representation of the byte array
     */
    @ToJson
    public String serialize(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * Deserializes a Base64-encoded string back to a byte array.
     *
     * @param data the Base64-encoded string to deserialize
     * @return the decoded byte array
     */
    @FromJson
    public byte[] deserialize(String data) {
        return Base64.getDecoder().decode(data);
    }
}
