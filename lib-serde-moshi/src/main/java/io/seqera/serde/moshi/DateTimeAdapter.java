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

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

/**
 * Date time adapter for Moshi JSON serialization.
 *
 * <p>This adapter handles the serialization and deserialization of Java 8 date/time types
 * ({@link Instant} and {@link Duration}) in JSON format. It uses ISO-8601 format for
 * Instant values and nanosecond precision for Duration values.</p>
 *
 * <p>For backward compatibility, the Duration deserializer supports both the legacy
 * floating-point format (seconds as a decimal) and the current long format (nanoseconds).</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class DateTimeAdapter {

    /**
     * Serializes an Instant to an ISO-8601 formatted string.
     *
     * @param value the Instant to serialize
     * @return the ISO-8601 formatted string, or null if value is null
     */
    @ToJson
    public String serializeInstant(Instant value) {
        return value != null ? DateTimeFormatter.ISO_INSTANT.format(value) : null;
    }

    /**
     * Deserializes an ISO-8601 formatted string to an Instant.
     *
     * @param value the ISO-8601 formatted string to deserialize
     * @return the deserialized Instant, or null if value is null
     */
    @FromJson
    public Instant deserializeInstant(String value) {
        return value != null ? Instant.from(DateTimeFormatter.ISO_INSTANT.parse(value)) : null;
    }

    /**
     * Serializes a Duration to its nanosecond representation as a string.
     *
     * @param value the Duration to serialize
     * @return the nanosecond value as a string, or null if value is null
     */
    @ToJson
    public String serializeDuration(Duration value) {
        return value != null ? String.valueOf(value.toNanos()) : null;
    }

    /**
     * Deserializes a string representation of a Duration.
     *
     * <p>This method supports two formats for backward compatibility:
     * <ul>
     *   <li>Long format: nanoseconds as a whole number (current format)</li>
     *   <li>Float format: seconds as a decimal number (legacy format)</li>
     * </ul>
     *
     * @param value the string representation to deserialize
     * @return the deserialized Duration, or null if value is null
     */
    @FromJson
    public Duration deserializeDuration(String value) {
        if (value == null) {
            return null;
        }
        // For backward compatibility: duration may be encoded as float value
        // instead of long (number of nanoseconds) as expected
        final long val0 = value.contains(".")
            ? Math.round(Double.parseDouble(value) * 1_000_000_000)
            : Long.parseLong(value);
        return Duration.ofNanos(val0);
    }
}
