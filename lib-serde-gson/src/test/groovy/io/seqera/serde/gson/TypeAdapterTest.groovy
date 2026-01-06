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

package io.seqera.serde.gson

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.seqera.serde.gson.adapters.DurationAdapter
import io.seqera.serde.gson.adapters.InstantAdapter
import io.seqera.serde.gson.adapters.LocalDateAdapter
import io.seqera.serde.gson.adapters.LocalDateTimeAdapter
import io.seqera.serde.gson.adapters.LocalTimeAdapter
import io.seqera.serde.gson.adapters.OffsetDateTimeAdapter
import spock.lang.Specification

/**
 * Tests for individual type adapters.
 *
 * @author Paolo Di Tommaso
 */
class TypeAdapterTest extends Specification {

    // === InstantAdapter Tests ===

    def 'InstantAdapter should serialize to ISO-8601'() {
        given:
        def adapter = new InstantAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)
        def instant = Instant.parse('2025-01-06T10:30:00Z')

        when:
        adapter.write(jsonWriter, instant)

        then:
        writer.toString() == '"2025-01-06T10:30:00Z"'
    }

    def 'InstantAdapter should handle null on write'() {
        given:
        def adapter = new InstantAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, null)

        then:
        writer.toString() == 'null'
    }

    def 'InstantAdapter should deserialize ISO-8601 string'() {
        given:
        def adapter = new InstantAdapter()
        def reader = new JsonReader(new StringReader('"2025-01-06T10:30:00Z"'))

        when:
        def result = adapter.read(reader)

        then:
        result == Instant.parse('2025-01-06T10:30:00Z')
    }

    def 'InstantAdapter should handle null on read'() {
        given:
        def adapter = new InstantAdapter()
        def reader = new JsonReader(new StringReader('null'))

        when:
        def result = adapter.read(reader)

        then:
        result == null
    }

    def 'InstantAdapter should handle epoch with milliseconds'() {
        given:
        def adapter = new InstantAdapter()
        def instant = Instant.parse('2025-01-06T10:30:00.123Z')
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, instant)
        def reader = new JsonReader(new StringReader(writer.toString()))
        def result = adapter.read(reader)

        then:
        result == instant
    }

    // === DurationAdapter Tests ===

    def 'DurationAdapter should serialize to ISO-8601'() {
        given:
        def adapter = new DurationAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)
        def duration = Duration.ofHours(2).plusMinutes(30)

        when:
        adapter.write(jsonWriter, duration)

        then:
        writer.toString() == '"PT2H30M"'
    }

    def 'DurationAdapter should handle null on write'() {
        given:
        def adapter = new DurationAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, null)

        then:
        writer.toString() == 'null'
    }

    def 'DurationAdapter should deserialize ISO-8601 string'() {
        given:
        def adapter = new DurationAdapter()
        def reader = new JsonReader(new StringReader('"PT2H30M"'))

        when:
        def result = adapter.read(reader)

        then:
        result == Duration.ofHours(2).plusMinutes(30)
    }

    def 'DurationAdapter should handle null on read'() {
        given:
        def adapter = new DurationAdapter()
        def reader = new JsonReader(new StringReader('null'))

        when:
        def result = adapter.read(reader)

        then:
        result == null
    }

    def 'DurationAdapter should handle complex durations'() {
        given:
        def adapter = new DurationAdapter()
        def duration = Duration.ofDays(1).plusHours(2).plusMinutes(30).plusSeconds(45)
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, duration)
        def reader = new JsonReader(new StringReader(writer.toString()))
        def result = adapter.read(reader)

        then:
        result == duration
    }

    // === OffsetDateTimeAdapter Tests ===

    def 'OffsetDateTimeAdapter should serialize with timezone offset'() {
        given:
        def adapter = new OffsetDateTimeAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)
        def odt = OffsetDateTime.of(2025, 1, 6, 10, 30, 0, 0, ZoneOffset.ofHours(2))

        when:
        adapter.write(jsonWriter, odt)

        then:
        writer.toString() == '"2025-01-06T10:30+02:00"'
    }

    def 'OffsetDateTimeAdapter should handle null on write'() {
        given:
        def adapter = new OffsetDateTimeAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, null)

        then:
        writer.toString() == 'null'
    }

    def 'OffsetDateTimeAdapter should preserve timezone offset'() {
        given:
        def adapter = new OffsetDateTimeAdapter()
        def odt = OffsetDateTime.of(2025, 1, 6, 10, 30, 0, 0, ZoneOffset.ofHours(-5))
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, odt)
        def reader = new JsonReader(new StringReader(writer.toString()))
        def result = adapter.read(reader)

        then:
        result == odt
        result.offset == ZoneOffset.ofHours(-5)
    }

    def 'OffsetDateTimeAdapter should handle null on read'() {
        given:
        def adapter = new OffsetDateTimeAdapter()
        def reader = new JsonReader(new StringReader('null'))

        when:
        def result = adapter.read(reader)

        then:
        result == null
    }

    // === LocalDateTimeAdapter Tests ===

    def 'LocalDateTimeAdapter should serialize without timezone'() {
        given:
        def adapter = new LocalDateTimeAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)
        def ldt = LocalDateTime.of(2025, 1, 6, 10, 30, 0)

        when:
        adapter.write(jsonWriter, ldt)

        then:
        writer.toString() == '"2025-01-06T10:30"'
    }

    def 'LocalDateTimeAdapter should handle null on write'() {
        given:
        def adapter = new LocalDateTimeAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, null)

        then:
        writer.toString() == 'null'
    }

    def 'LocalDateTimeAdapter should deserialize correctly'() {
        given:
        def adapter = new LocalDateTimeAdapter()
        def reader = new JsonReader(new StringReader('"2025-01-06T10:30:45"'))

        when:
        def result = adapter.read(reader)

        then:
        result == LocalDateTime.of(2025, 1, 6, 10, 30, 45)
    }

    def 'LocalDateTimeAdapter should handle null on read'() {
        given:
        def adapter = new LocalDateTimeAdapter()
        def reader = new JsonReader(new StringReader('null'))

        when:
        def result = adapter.read(reader)

        then:
        result == null
    }

    // === LocalDateAdapter Tests ===

    def 'LocalDateAdapter should serialize date only'() {
        given:
        def adapter = new LocalDateAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)
        def date = LocalDate.of(2025, 1, 6)

        when:
        adapter.write(jsonWriter, date)

        then:
        writer.toString() == '"2025-01-06"'
    }

    def 'LocalDateAdapter should handle null on write'() {
        given:
        def adapter = new LocalDateAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, null)

        then:
        writer.toString() == 'null'
    }

    def 'LocalDateAdapter should deserialize correctly'() {
        given:
        def adapter = new LocalDateAdapter()
        def reader = new JsonReader(new StringReader('"2025-01-06"'))

        when:
        def result = adapter.read(reader)

        then:
        result == LocalDate.of(2025, 1, 6)
    }

    def 'LocalDateAdapter should handle null on read'() {
        given:
        def adapter = new LocalDateAdapter()
        def reader = new JsonReader(new StringReader('null'))

        when:
        def result = adapter.read(reader)

        then:
        result == null
    }

    // === LocalTimeAdapter Tests ===

    def 'LocalTimeAdapter should serialize time only'() {
        given:
        def adapter = new LocalTimeAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)
        def time = LocalTime.of(10, 30, 45)

        when:
        adapter.write(jsonWriter, time)

        then:
        writer.toString() == '"10:30:45"'
    }

    def 'LocalTimeAdapter should handle null on write'() {
        given:
        def adapter = new LocalTimeAdapter()
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, null)

        then:
        writer.toString() == 'null'
    }

    def 'LocalTimeAdapter should deserialize correctly'() {
        given:
        def adapter = new LocalTimeAdapter()
        def reader = new JsonReader(new StringReader('"10:30:45"'))

        when:
        def result = adapter.read(reader)

        then:
        result == LocalTime.of(10, 30, 45)
    }

    def 'LocalTimeAdapter should handle null on read'() {
        given:
        def adapter = new LocalTimeAdapter()
        def reader = new JsonReader(new StringReader('null'))

        when:
        def result = adapter.read(reader)

        then:
        result == null
    }

    def 'LocalTimeAdapter should handle nanoseconds'() {
        given:
        def adapter = new LocalTimeAdapter()
        def time = LocalTime.of(10, 30, 45, 123456789)
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, time)
        def reader = new JsonReader(new StringReader(writer.toString()))
        def result = adapter.read(reader)

        then:
        result == time
        result.nano == 123456789
    }

    def 'LocalTimeAdapter should handle midnight'() {
        given:
        def adapter = new LocalTimeAdapter()
        def time = LocalTime.MIDNIGHT
        def writer = new StringWriter()
        def jsonWriter = new JsonWriter(writer)

        when:
        adapter.write(jsonWriter, time)
        def reader = new JsonReader(new StringReader(writer.toString()))
        def result = adapter.read(reader)

        then:
        result == LocalTime.MIDNIGHT
    }
}
