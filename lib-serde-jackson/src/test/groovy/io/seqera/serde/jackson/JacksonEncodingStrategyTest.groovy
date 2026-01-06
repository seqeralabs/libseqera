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

package io.seqera.serde.jackson

import java.time.Duration
import java.time.Instant

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.Canonical
import spock.lang.Specification

class JacksonEncodingStrategyTest extends Specification {

    @Canonical
    static class TestBean implements JacksonSerializable {
        String name
        int count
        Instant timestamp
        Duration duration
    }

    @Canonical
    static class SimpleBean implements JacksonSerializable {
        String foo
        String bar
    }

    def 'should encode and decode simple object'() {
        given:
        def encoder = new JacksonEncodingStrategy<SimpleBean>() {}
        def bean = new SimpleBean('hello', 'world')

        when:
        def json = encoder.encode(bean)

        then:
        json.contains('"foo":"hello"')
        json.contains('"bar":"world"')

        when:
        def decoded = encoder.decode(json)

        then:
        decoded.foo == 'hello'
        decoded.bar == 'world'
    }

    def 'should encode and decode object with date/time fields'() {
        given:
        def encoder = new JacksonEncodingStrategy<TestBean>() {}
        def bean = new TestBean('test', 42, Instant.parse('2025-01-01T00:00:00Z'), Duration.ofHours(1))

        when:
        def json = encoder.encode(bean)

        then:
        json.contains('"name":"test"')
        json.contains('"count":42')
        json.contains('"timestamp":"2025-01-01T00:00:00Z"')

        when:
        def decoded = encoder.decode(json)

        then:
        decoded.name == 'test'
        decoded.count == 42
        decoded.timestamp == Instant.parse('2025-01-01T00:00:00Z')
        decoded.duration == Duration.ofHours(1)
    }

    def 'should handle null values'() {
        given:
        def encoder = new JacksonEncodingStrategy<TestBean>() {}

        expect:
        encoder.encode(null) == null
        encoder.decode(null) == null
    }

    def 'should work with pre-configured ObjectMapper'() {
        given:
        def objectMapper = new ObjectMapper()
        def encoder = new JacksonEncodingStrategy<SimpleBean>(objectMapper) {}
        def bean = new SimpleBean('x', 'y')

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        decoded.foo == 'x'
        decoded.bar == 'y'
    }

    def 'should throw RuntimeException on invalid JSON'() {
        given:
        def encoder = new JacksonEncodingStrategy<SimpleBean>() {}

        when:
        encoder.decode('invalid json')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Failed to decode JSON')
    }

    def 'should ignore unknown properties during deserialization'() {
        given:
        def encoder = new JacksonEncodingStrategy<SimpleBean>() {}
        def jsonWithUnknownFields = '{"foo":"hello","bar":"world","unknownField":"ignored","anotherUnknown":123}'

        when:
        def decoded = encoder.decode(jsonWithUnknownFields)

        then:
        noExceptionThrown()
        decoded.foo == 'hello'
        decoded.bar == 'world'
    }
}
