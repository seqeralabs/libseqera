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

package io.seqera.serde.gson.adapters;

import java.io.IOException;
import java.time.Duration;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Gson TypeAdapter for {@link Duration} that serializes to ISO-8601 format.
 * <p>
 * Example output: {@code "PT2H30M"} for 2 hours and 30 minutes.
 *
 * @author Paolo Di Tommaso
 */
public class DurationAdapter extends TypeAdapter<Duration> {

    @Override
    public void write(JsonWriter writer, Duration value) throws IOException {
        writer.value(value != null ? value.toString() : null);
    }

    @Override
    public Duration read(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return Duration.parse(reader.nextString());
    }
}
