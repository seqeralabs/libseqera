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

package io.seqera.data.store.state

import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

import groovy.transform.Canonical
import groovy.transform.EqualsAndHashCode
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.serde.encode.StringEncodingStrategy
import io.seqera.serde.moshi.MoshiEncodeStrategy
import io.seqera.data.store.state.impl.StateProvider
import io.seqera.data.store.state.impl.LocalStateProvider
import jakarta.inject.Inject
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
class VersionedStateStoreTest extends Specification {

    @Inject LocalStateProvider provider

    static public long ttlMillis = 100

    @Canonical
    @EqualsAndHashCode(excludes = 'ver')
    static class MyObject implements VersionAware<MyObject> {
        String field1
        String field2
        long ver

        @Override
        long version() { return ver }

        @Override
        MyObject withVersion(long version) {
            return new MyObject(field1, field2, version)
        }
    }

    static class MyState extends MyObject implements RequestIdAware {

        MyState(String field1, String field2) {
            super(field1, field2)
        }

        @Override
        String getRequestId() {
            return field1
        }

        @Override
        MyState withVersion(long version) {
            final result = new MyState(field1, field2)
            result.ver = version
            return result
        }
    }

    static class MyCacheStore extends VersionedStateStore<MyObject> {

        MyCacheStore(StateProvider<String, String> provider) {
            super(provider, new MoshiEncodeStrategy<MyObject>() {})
        }

        @Override
        protected String getPrefix() {
            return 'test/v1'
        }

        @Override
        protected Duration getDuration() {
            return Duration.ofMillis(ttlMillis)
        }
    }

    def 'should get and put a value' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()

        expect:
        store.get(key) == null

        when:
        store.put(key, new MyObject('this','that'))
        then:
        store.get(key) == new MyObject('this','that')

        when:
        sleep ttlMillis *2
        then:
        store.get(key) == null
    }

    def 'should replace a versioned value and stamp a new version' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'), Duration.ofSeconds(10))

        expect: 'the stored form is framed with a version stamped at creation'
        provider.get(store.key0(key)) =~ /^\{"@v":\d+,/

        when:
        def current = store.get(key)
        then:
        current.version() != 0

        when: 'replace using the version from the current read'
        def done = store.replaceIf(key, new MyObject('c', 'd', current.version()))
        then:
        done
        and: 'the stored value carries a freshly stamped version'
        store.get(key) == new MyObject('c', 'd')
        store.get(key).version() != current.version()
        store.get(key).version() != 0
    }

    def 'should reject a versioned value that does not serialize to a JSON object' () {
        given:
        def store = new NotJsonStore(provider)

        when:
        store.put('k1', new MyObject('a', 'b'))
        then:
        thrown(IllegalStateException)
    }

    def 'should refuse the replace when the caller version is stale' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'), Duration.ofSeconds(10))
        and: 'a concurrent writer lands a versioned replace first'
        def current = store.get(key)
        store.replaceIf(key, new MyObject('x', 'y', current.version()))

        expect: 'a replace based on the pre-interference read is refused'
        !store.replaceIf(key, new MyObject('c', 'd', current.version()))
        and: 'the concurrent write is preserved'
        store.get(key) == new MyObject('x', 'y', 1)
    }

    def 'should refuse the replace when the key is missing' () {
        given:
        def store = new MyCacheStore(provider)

        expect:
        !store.replaceIf(UUID.randomUUID().toString(), new MyObject('a', 'b'))
    }

    def 'should treat a frame whose version does not fit a long as user data' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        and: 'a foreign entry whose head resembles a frame but overflows a long'
        provider.put(store.key0(key), '{"@v":99999999999999999999,"field1":"a","field2":"b"}', Duration.ofSeconds(10))

        when:
        def value = store.get(key)
        then: 'the read degrades to an unframed decode instead of throwing'
        value == new MyObject('a', 'b')
        value.version() == 0
    }

    def 'should strip the frame on read and recover the version from it' () {
        given:
        def store = new VersionlessStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'))

        expect: 'the stored form is framed'
        provider.get(store.key0(key)) =~ /^\{"@v":\d+,/

        when: 'reading through the store'
        def current = store.get(key)
        then: 'the frame never reaches the decoder and the version is injected from the frame'
        current == new MyObject('a', 'b')
        current.version() != 0

        when: 'the recovered version acts as a valid witness'
        def done = store.replaceIf(key, new MyObject('c', 'd', current.version()))
        then:
        done
        store.get(key) == new MyObject('c', 'd')
        store.get(key).version() != current.version()
    }

    def 'should report the frame as the authoritative version on read' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        and: 'an unframed legacy entry whose payload carries a spurious version field'
        provider.put(store.key0(key), '{"field1":"a","field2":"b","ver":99}', Duration.ofSeconds(10))

        when:
        def legacy = store.get(key)
        then: 'the missing frame counts as version zero, whatever the payload says'
        legacy == new MyObject('a', 'b')
        legacy.version() == 0

        and: 'zero is the witness that adopts the entry'
        store.replaceIf(key, new MyObject('c', 'd', legacy.version()))
    }

    def 'should frame and unframe an empty JSON object payload' () {
        given:
        def store = new EmptyJsonStore(provider)
        def key = UUID.randomUUID().toString()

        when:
        store.put(key, new MyObject('x', 'y'))
        then: 'the frame is the whole stored form'
        provider.get(store.key0(key)) =~ /^\{"@v":\d+\}$/
        and: 'the decoder gets back the exact empty object it produced'
        store.get(key) == new MyObject()
        store.get(key).version() != 0
    }

    def 'should strip the frame from the value returned by put-if-absent-and-count' () {
        given:
        def store = new VersionlessStore(provider)
        def key = UUID.randomUUID().toString()

        when:
        def result = store.putIfAbsentAndCount(key, new MyObject('a', 'b'))
        then:
        result.succeed
        result.value == new MyObject('a', 'b')
        result.value.version() != 0
    }

    def 'should converge the versioned replace even when the encoding is not deterministic' () {
        given:
        def store = new NonDeterministicStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'))

        when:
        def current = store.get(key)
        def done = store.replaceIf(key, new MyObject(current.field1, 'updated', current.version()))
        then:
        done
        store.get(key) == new MyObject('a', 'updated', 1)
    }

    def 'should adopt a legacy entry without version at version zero' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        and: 'an entry stored before versioning existed'
        provider.put(store.key0(key), '{"field1":"a","field2":"b"}', Duration.ofSeconds(10))

        when:
        def legacy = store.get(key)
        then:
        legacy == new MyObject('a', 'b')
        legacy.version() == 0

        when: 'the first versioned replace adopts the entry'
        def done = store.replaceIf(key, new MyObject('c', 'd', legacy.version()))
        then:
        done
        store.get(key).version() != 0
        and: 'the adopted entry is framed'
        provider.get(store.key0(key)) =~ /^\{"@v":\d+,/
    }

    def 'should reset the ttl on a versioned replace like any other write' () {
        given:
        def TTL = 600
        def HALF = Math.round(TTL * 0.66)
        def store = new MyCacheStore(provider) {
            @Override
            protected Duration getDuration() { return Duration.ofMillis(TTL) }
        }
        def recId = UUID.randomUUID().toString()
        def key = UUID.randomUUID().toString()

        when: 'an entry near its deadline is replaced without an explicit ttl'
        store.put(key, new MyObject('a', 'b'))
        sleep(HALF)
        def current = store.get(key)
        def done = store.replaceIf(key, new MyState(recId, 'value').withVersion(current.version()))
        then:
        done

        and: 'past the original deadline the entry and its request-id mapping are both alive'
        sleep(HALF)
        store.get(key) == new MyState(recId, 'value')
        store.findByRequestId(recId) == new MyState(recId, 'value')
    }

    def 'should read a foreign overflow head at version zero without adopting it' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        and: 'a foreign entry whose head resembles a frame but overflows a long'
        provider.put(store.key0(key), '{"@v":99999999999999999999,"field1":"a","field2":"b"}', Duration.ofSeconds(10))

        when: 'the version recovered on read acts as the witness'
        def value = store.get(key)
        def done = store.replaceIf(key, new MyObject('c', 'd', value.version()))
        then: 'the read degrades to version zero but the replace never matches the foreign head'
        value.version() == 0
        !done
        and: 'the entry is left untouched'
        provider.get(store.key0(key)) == '{"@v":99999999999999999999,"field1":"a","field2":"b"}'
    }

    def 'should read a foreign head overrunning the inspected window at version zero without adopting it' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        and: 'a foreign entry whose frame-looking digit run leaves no terminator in the inspected head window'
        provider.put(store.key0(key), '{"@v":9999999999999999999999,"field1":"a","field2":"b"}', Duration.ofSeconds(10))

        when: 'the version recovered on read acts as the witness'
        def value = store.get(key)
        def done = store.replaceIf(key, new MyObject('c', 'd', value.version()))
        then: 'the read degrades to version zero but the replace never matches the foreign head'
        value.version() == 0
        !done
        and: 'the entry is left untouched'
        provider.get(store.key0(key)) == '{"@v":9999999999999999999999,"field1":"a","field2":"b"}'
    }

    def 'should reject a provider that does not support versioned writes' () {
        when: 'a versioned store is built on a provider that is not a VersionProvider'
        new MyCacheStore(Stub(StateProvider))
        then:
        thrown(IllegalArgumentException)
    }

    def 'should record the request-id mapping on a versioned replace' () {
        given:
        def store = new MyCacheStore(provider)
        def recId = UUID.randomUUID().toString()
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'), Duration.ofSeconds(10))

        when:
        def current = store.get(key)
        def done = store.replaceIf(key, new MyState(recId, 'value').withVersion(current.version()))
        then:
        done
        and:
        store.get(key).version() != current.version()
        store.findByRequestId(recId) == new MyState(recId, 'value')
    }

    /**
     * Encoder whose output differs on every call — the single-process equivalent of two
     * replicas whose JVMs serialize the same value with different bytes (property order,
     * spacing). A store using it can never reproduce the raw form it previously wrote,
     * so any CAS comparing a re-serialization is bound to fail.
     */
    static class NonDeterministicEncoder implements StringEncodingStrategy<MyObject> {

        private final AtomicInteger nonce = new AtomicInteger()

        @Override
        String encode(MyObject value) {
            final n = nonce.incrementAndGet()
            final parts = [
                    "\"field1\":\"${value.field1}\"".toString(),
                    "\"field2\":\"${value.field2}\"".toString(),
                    "\"ver\":${value.version()}".toString() ]
            Collections.rotate(parts, n % 3)
            return '{' + parts.join(',' + ' ' * (n % 3)) + '}'
        }

        @Override
        MyObject decode(String encoded) {
            // field-wise extraction: tolerates any property order and unknown fields
            final f1 = (encoded =~ /"field1":"([^"]*)"/)
            final f2 = (encoded =~ /"field2":"([^"]*)"/)
            final ver = (encoded =~ /"ver":(\d+)/)
            return new MyObject(
                    f1.find() ? f1.group(1) : null,
                    f2.find() ? f2.group(1) : null,
                    ver.find() ? ver.group(1) as long : 0L )
        }
    }

    static class NotJsonEncoder implements StringEncodingStrategy<MyObject> {

        @Override
        String encode(MyObject value) {
            return "${value.field1}|${value.field2}|${value.version()}"
        }

        @Override
        MyObject decode(String encoded) {
            final parts = encoded.tokenize('|')
            return new MyObject(parts[0], parts[1], parts[2] as long)
        }
    }

    static class NotJsonStore extends VersionedStateStore<MyObject> {

        NotJsonStore(StateProvider<String, String> provider) {
            super(provider, new NotJsonEncoder())
        }

        @Override
        protected String getPrefix() {
            return 'test/v1'
        }

        @Override
        protected Duration getDuration() {
            return Duration.ofSeconds(10)
        }
    }

    static class NonDeterministicStore extends VersionedStateStore<MyObject> {

        NonDeterministicStore(StateProvider<String, String> provider) {
            super(provider, new NonDeterministicEncoder())
        }

        @Override
        protected String getPrefix() {
            return 'test/v1'
        }

        @Override
        protected Duration getDuration() {
            return Duration.ofSeconds(10)
        }
    }

    /**
     * Encoder whose serialized form does not carry the version at all and whose decoder
     * rejects any input other than what {@code encode} produced — the strictest possible
     * consumer: the version must travel in the store's frame only, and the frame must
     * never leak to the decoder.
     */
    static class VersionlessEncoder implements StringEncodingStrategy<MyObject> {

        @Override
        String encode(MyObject value) {
            return '{"field1":"' + value.field1 + '","field2":"' + value.field2 + '"}'
        }

        @Override
        MyObject decode(String encoded) {
            if( encoded.contains('"@v"') )
                throw new IllegalStateException("Version frame leaked to the decoder - offending payload: $encoded")
            final f1 = (encoded =~ /"field1":"([^"]*)"/)
            final f2 = (encoded =~ /"field2":"([^"]*)"/)
            return new MyObject(
                    f1.find() ? f1.group(1) : null,
                    f2.find() ? f2.group(1) : null )
        }
    }

    static class VersionlessStore extends VersionedStateStore<MyObject> {

        VersionlessStore(StateProvider<String, String> provider) {
            super(provider, new VersionlessEncoder())
        }

        @Override
        protected String getPrefix() {
            return 'test/v1'
        }

        @Override
        protected Duration getDuration() {
            return Duration.ofSeconds(10)
        }
    }

    static class EmptyJsonEncoder implements StringEncodingStrategy<MyObject> {

        @Override
        String encode(MyObject value) { '{}' }

        @Override
        MyObject decode(String encoded) {
            if( encoded != '{}' )
                throw new IllegalStateException("Expected the exact payload produced by encode - offending payload: $encoded")
            return new MyObject()
        }
    }

    static class EmptyJsonStore extends VersionedStateStore<MyObject> {

        EmptyJsonStore(StateProvider<String, String> provider) {
            super(provider, new EmptyJsonEncoder())
        }

        @Override
        protected String getPrefix() {
            return 'test/v1'
        }

        @Override
        protected Duration getDuration() {
            return Duration.ofSeconds(10)
        }
    }

}
