/*
 * Copyright 2024-2025, Seqera Labs
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
 */

package io.seqera.serde.gson

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

import spock.lang.Specification

/**
 * Tests for {@link GsonEncodingStrategy}
 *
 * @author Paolo Di Tommaso
 */
class GsonEncodingStrategyTest extends Specification {

    // === Test Beans ===

    static class SimpleBean {
        String foo
        String bar
    }

    static class PrimitiveBean {
        String name
        int intValue
        long longValue
        double doubleValue
        boolean boolValue
    }

    static class DateTimeBean {
        String name
        Instant instant
        Duration duration
        OffsetDateTime offsetDateTime
        LocalDateTime localDateTime
        LocalDate localDate
        LocalTime localTime
    }

    static class NestedBean {
        String id
        SimpleBean nested
    }

    static class CollectionBean {
        List<String> tags
        Map<String, Object> metadata
    }

    // === Basic Encode/Decode Tests ===

    def 'should encode and decode simple object'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
        def bean = new SimpleBean(foo: 'hello', bar: 'world')

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

    def 'should encode and decode object with all primitive types'() {
        given:
        def encoder = new GsonEncodingStrategy<PrimitiveBean>() {}
        def bean = new PrimitiveBean(
            name: 'test',
            intValue: 42,
            longValue: 123456789L,
            doubleValue: 3.14159,
            boolValue: true
        )

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        decoded.name == 'test'
        decoded.intValue == 42
        decoded.longValue == 123456789L
        decoded.doubleValue == 3.14159
        decoded.boolValue == true
    }

    def 'should handle nested objects'() {
        given:
        def encoder = new GsonEncodingStrategy<NestedBean>() {}
        def bean = new NestedBean(
            id: 'parent',
            nested: new SimpleBean(foo: 'child-foo', bar: 'child-bar')
        )

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        decoded.id == 'parent'
        decoded.nested.foo == 'child-foo'
        decoded.nested.bar == 'child-bar'
    }

    def 'should handle collections'() {
        given:
        def encoder = new GsonEncodingStrategy<CollectionBean>() {}
        def bean = new CollectionBean(
            tags: ['java', 'groovy', 'kotlin'],
            metadata: [key1: 'value1', key2: 123]
        )

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        decoded.tags == ['java', 'groovy', 'kotlin']
        decoded.metadata.key1 == 'value1'
        // Note: Gson deserializes numbers as Double by default
        decoded.metadata.key2 == 123.0
    }

    // === Null Handling Tests ===

    def 'should return null when encoding null'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}

        expect:
        encoder.encode(null) == null
    }

    def 'should return null when decoding null'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}

        expect:
        encoder.decode(null) == null
    }

    def 'should handle null fields in object'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
        def bean = new SimpleBean(foo: 'hello', bar: null)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        decoded.foo == 'hello'
        decoded.bar == null
        // By default, null fields are not included
        !json.contains('"bar"')
    }

    // === Date/Time Types Tests ===

    def 'should encode and decode Instant'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def instant = Instant.parse('2025-01-01T00:00:00Z')
        def bean = new DateTimeBean(name: 'test', instant: instant)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        json.contains('"instant":"2025-01-01T00:00:00Z"')
        decoded.instant == instant
    }

    def 'should encode and decode Duration'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def duration = Duration.ofHours(1).plusMinutes(30)
        def bean = new DateTimeBean(name: 'test', duration: duration)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        json.contains('"duration":"PT1H30M"')
        decoded.duration == duration
    }

    def 'should encode and decode OffsetDateTime'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def offsetDateTime = OffsetDateTime.of(2025, 1, 6, 10, 30, 0, 0, ZoneOffset.ofHours(2))
        def bean = new DateTimeBean(name: 'test', offsetDateTime: offsetDateTime)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        json.contains('"offsetDateTime":"2025-01-06T10:30+02:00"')
        decoded.offsetDateTime == offsetDateTime
    }

    def 'should encode and decode LocalDateTime'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def localDateTime = LocalDateTime.of(2025, 1, 6, 10, 30, 0)
        def bean = new DateTimeBean(name: 'test', localDateTime: localDateTime)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        json.contains('"localDateTime":"2025-01-06T10:30"')
        decoded.localDateTime == localDateTime
    }

    def 'should encode and decode LocalDate'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def localDate = LocalDate.of(2025, 1, 6)
        def bean = new DateTimeBean(name: 'test', localDate: localDate)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        json.contains('"localDate":"2025-01-06"')
        decoded.localDate == localDate
    }

    def 'should encode and decode LocalTime'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def localTime = LocalTime.of(10, 30, 45)
        def bean = new DateTimeBean(name: 'test', localTime: localTime)

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        json.contains('"localTime":"10:30:45"')
        decoded.localTime == localTime
    }

    def 'should encode and decode object with all date/time fields'() {
        given:
        def encoder = new GsonEncodingStrategy<DateTimeBean>() {}
        def bean = new DateTimeBean(
            name: 'full-test',
            instant: Instant.parse('2025-01-01T12:00:00Z'),
            duration: Duration.ofMinutes(90),
            offsetDateTime: OffsetDateTime.of(2025, 6, 15, 14, 30, 0, 0, ZoneOffset.ofHours(-5)),
            localDateTime: LocalDateTime.of(2025, 3, 20, 9, 15, 30),
            localDate: LocalDate.of(2025, 12, 25),
            localTime: LocalTime.of(23, 59, 59, 999999999)
        )

        when:
        def json = encoder.encode(bean)
        def decoded = encoder.decode(json)

        then:
        decoded.name == bean.name
        decoded.instant == bean.instant
        decoded.duration == bean.duration
        decoded.offsetDateTime == bean.offsetDateTime
        decoded.localDateTime == bean.localDateTime
        decoded.localDate == bean.localDate
        decoded.localTime == bean.localTime
    }

    // === Configuration Options Tests ===

    def 'should format JSON with pretty print enabled'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
            .withPrettyPrint(true)
        def bean = new SimpleBean(foo: 'hello', bar: 'world')

        when:
        def json = encoder.encode(bean)

        then:
        json.contains('\n')
        json.contains('  ')
    }

    def 'should include null fields when serializeNulls enabled'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
            .withSerializeNulls(true)
        def bean = new SimpleBean(foo: 'hello', bar: null)

        when:
        def json = encoder.encode(bean)

        then:
        json.contains('"bar":null')
    }

    def 'should exclude null fields by default'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
        def bean = new SimpleBean(foo: 'hello', bar: null)

        when:
        def json = encoder.encode(bean)

        then:
        !json.contains('"bar"')
        json.contains('"foo":"hello"')
    }

    // === Error Handling Tests ===

    def 'should throw RuntimeException on invalid JSON'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}

        when:
        encoder.decode('not valid json')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Failed to decode JSON')
    }

    def 'should throw RuntimeException on malformed JSON'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}

        when:
        encoder.decode('{"foo": "hello", "bar":}')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Failed to decode JSON')
    }

    def 'should ignore unknown properties during deserialization'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
        def json = '{"foo": "hello", "bar": "world", "unknownField": "ignored", "anotherUnknown": 123}'

        when:
        def decoded = encoder.decode(json)

        then:
        noExceptionThrown()
        decoded.foo == 'hello'
        decoded.bar == 'world'
    }

    // === Custom TypeAdapterFactory Tests ===

    static interface Animal {}
    static class Dog implements Animal {
        String name
        int barkVolume
    }
    static class Cat implements Animal {
        String name
        boolean lazy
    }

    def 'should use custom TypeAdapterFactory'() {
        given:
        def factory = RuntimeTypeAdapterFactory.of(Animal, '@type')
            .registerSubtype(Dog, 'Dog')
            .registerSubtype(Cat, 'Cat')
        def encoder = new GsonEncodingStrategy<Animal>() {}
            .withTypeAdapterFactory(factory)
        def dog = new Dog(name: 'Rex', barkVolume: 10)

        when:
        def json = encoder.encode(dog)

        then:
        json.contains('"@type":"Dog"')
        json.contains('"name":"Rex"')
        json.contains('"barkVolume":10')

        when:
        def decoded = encoder.decode(json)

        then:
        decoded instanceof Dog
        ((Dog) decoded).name == 'Rex'
        ((Dog) decoded).barkVolume == 10
    }

    // === Method Chaining Tests ===

    def 'should support method chaining for configuration'() {
        given:
        def encoder = new GsonEncodingStrategy<SimpleBean>() {}
            .withPrettyPrint(true)
            .withSerializeNulls(true)
        def bean = new SimpleBean(foo: 'test', bar: null)

        when:
        def json = encoder.encode(bean)

        then:
        json.contains('\n')
        // With pretty print, Gson uses ": null" with space
        json.contains('"bar": null') || json.contains('"bar":null')
    }
}
