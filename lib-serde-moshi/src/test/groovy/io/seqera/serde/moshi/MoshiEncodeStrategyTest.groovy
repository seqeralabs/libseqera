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

package io.seqera.serde.moshi

import spock.lang.Specification

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Tests for MoshiEncodeStrategy and adapters
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class MoshiEncodeStrategyTest extends Specification {

    static class SimpleData {
        String name
        Integer value

        boolean equals(o) {
            if (this.is(o)) return true
            if (getClass() != o.class) return false
            SimpleData that = (SimpleData) o
            if (name != that.name) return false
            if (value != that.value) return false
            return true
        }

        int hashCode() {
            int result
            result = (name != null ? name.hashCode() : 0)
            result = 31 * result + (value != null ? value.hashCode() : 0)
            return result
        }
    }

    static class ComplexData {
        String id
        Instant timestamp
        Duration duration
        Path filePath
        URI location
        byte[] data
        OffsetDateTime offsetDateTime

        boolean equals(o) {
            if (this.is(o)) return true
            if (getClass() != o.class) return false
            ComplexData that = (ComplexData) o
            if (id != that.id) return false
            if (timestamp != that.timestamp) return false
            if (duration != that.duration) return false
            if (filePath != that.filePath) return false
            if (location != that.location) return false
            if (!Arrays.equals(data, that.data)) return false
            if (offsetDateTime != that.offsetDateTime) return false
            return true
        }

        int hashCode() {
            int result
            result = (id != null ? id.hashCode() : 0)
            result = 31 * result + (timestamp != null ? timestamp.hashCode() : 0)
            result = 31 * result + (duration != null ? duration.hashCode() : 0)
            result = 31 * result + (filePath != null ? filePath.hashCode() : 0)
            result = 31 * result + (location != null ? location.hashCode() : 0)
            result = 31 * result + Arrays.hashCode(data)
            result = 31 * result + (offsetDateTime != null ? offsetDateTime.hashCode() : 0)
            return result
        }
    }

    def 'should encode and decode simple data'() {
        given:
        def encoder = new MoshiEncodeStrategy<SimpleData>() {}
        and:
        def data = new SimpleData(name: 'test', value: 42)

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.class == data.class
        and:
        copy == data
    }

    def 'should handle null values'() {
        given:
        def encoder = new MoshiEncodeStrategy<SimpleData>() {}

        when:
        def json = encoder.encode(null)

        then:
        json == null

        when:
        def result = encoder.decode(null)

        then:
        result == null
    }

    def 'should encode and decode instant'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def timestamp = Instant.parse('2024-10-07T20:41:00.804699Z')
        def data = new ComplexData(
            id: '12345',
            timestamp: timestamp,
            duration: null,
            filePath: null,
            location: null,
            data: null,
            offsetDateTime: null
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.timestamp == timestamp
        and:
        json.contains('"timestamp":"2024-10-07T20:41:00.804699Z"')
    }

    def 'should encode and decode duration'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def duration = Duration.ofMinutes(5)
        def data = new ComplexData(
            id: '12345',
            timestamp: null,
            duration: duration,
            filePath: null,
            location: null,
            data: null,
            offsetDateTime: null
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.duration == duration
    }

    def 'should decode legacy duration format'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def json = '{"id":"100","timestamp":null,"duration":"60.000000000","filePath":null,"location":null,"data":null}'

        when:
        def result = encoder.decode(json)

        then:
        result.duration == Duration.ofSeconds(60)
    }

    def 'should encode and decode path'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def path = Path.of('/some/path')
        def data = new ComplexData(
            id: '12345',
            timestamp: null,
            duration: null,
            filePath: path,
            location: null,
            data: null,
            offsetDateTime: null
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.filePath == path
    }

    def 'should encode and decode uri'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def uri = URI.create('http://example.com/test')
        def data = new ComplexData(
            id: '12345',
            timestamp: null,
            duration: null,
            filePath: null,
            location: uri,
            data: null,
            offsetDateTime: null
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.location == uri
    }

    def 'should encode and decode byte array'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def bytes = 'hello world'.bytes
        def data = new ComplexData(
            id: '12345',
            timestamp: null,
            duration: null,
            filePath: null,
            location: null,
            data: bytes,
            offsetDateTime: null
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.data == bytes
        and:
        // Verify it's encoded as Base64
        json.contains('"data":"')
    }

    def 'should encode and decode offset date time'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def odt = OffsetDateTime.of(2024, 10, 7, 20, 41, 0, 804699000, ZoneOffset.UTC)
        def data = new ComplexData(
            id: '12345',
            timestamp: null,
            duration: null,
            filePath: null,
            location: null,
            data: null,
            offsetDateTime: odt
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy.offsetDateTime == odt
        and:
        json.contains('"offsetDateTime":"2024-10-07T20:41:00.804699Z"')
    }

    def 'should encode and decode all complex types together'() {
        given:
        def encoder = new MoshiEncodeStrategy<ComplexData>() {}
        and:
        def data = new ComplexData(
            id: '12345',
            timestamp: Instant.parse('2024-10-07T20:41:00.804699Z'),
            duration: Duration.ofMinutes(1),
            filePath: Path.of('/some/path'),
            location: URI.create('http://example.com'),
            data: 'test data'.bytes,
            offsetDateTime: OffsetDateTime.of(2024, 10, 7, 20, 41, 0, 0, ZoneOffset.ofHours(2))
        )

        when:
        def json = encoder.encode(data)
        and:
        def copy = encoder.decode(json)

        then:
        copy == data
    }
}
