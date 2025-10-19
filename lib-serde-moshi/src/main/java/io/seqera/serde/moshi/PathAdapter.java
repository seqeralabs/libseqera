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

import java.nio.file.Path;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

/**
 * Moshi adapter for {@link Path} serialization.
 *
 * <p>This adapter handles the conversion between Path objects and their string
 * representation in JSON format. Note that it only supports the default file
 * system provider and does not handle custom file system implementations.</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class PathAdapter {

    /**
     * Serializes a Path to its string representation.
     *
     * @param path the Path to serialize
     * @return the string representation of the path, or null if path is null
     */
    @ToJson
    public String serialize(Path path) {
        return path != null ? path.toString() : null;
    }

    /**
     * Deserializes a string to a Path object.
     *
     * @param data the string representation of the path
     * @return the Path object, or null if data is null
     */
    @FromJson
    public Path deserialize(String data) {
        return data != null ? Path.of(data) : null;
    }
}
