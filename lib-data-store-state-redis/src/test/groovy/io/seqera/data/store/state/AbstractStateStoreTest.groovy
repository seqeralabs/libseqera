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
class AbstractStateStoreTest extends Specification {

    @Inject LocalStateProvider provider

    static public long ttlMillis = 100

    @Canonical
    static class MyObject implements Versioned<MyObject> {
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

    @Canonical
    static class PlainObject {
        String field1
    }

    static class PlainStore extends AbstractStateStore<PlainObject> {

        PlainStore(StateProvider<String, String> provider) {
            super(provider, new MoshiEncodeStrategy<PlainObject>() {})
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

    static class MyCacheStore extends AbstractStateStore<MyObject> {

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

    def 'should get key' () {
        given:
        def store = new MyCacheStore(provider)
        
        expect:
        store.key0('one') == 'test/v1:one'
    }

    def 'should get record id' () {
        given:
        def store = new MyCacheStore(provider)

        expect:
        store.requestId0('one') == 'test/v1/request-id:one'
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
    }

    def 'should replace a versioned value and bump its version' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'), Duration.ofSeconds(10))

        expect: 'the stored form is framed with the value own version'
        provider.get(store.key0(key)).startsWith('{"@v":0,')

        when:
        def current = store.get(key)
        then:
        current.version() == 0

        when: 'replace using the version from the current read'
        def done = store.replaceIf(key, new MyObject('c', 'd', current.version()))
        then:
        done
        and: 'the stored value carries the bumped version'
        store.get(key) == new MyObject('c', 'd', 1)
        provider.get(store.key0(key)).startsWith('{"@v":1,')
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

    def 'should get and put a value with custom ttl' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()

        expect:
        store.get(key) == null

        when:
        store.put(key, new MyObject('this','that'), Duration.ofSeconds(10))
        then:
        store.get(key) == new MyObject('this','that')

        when:
        sleep ttlMillis *2
        then:
        store.get(key) == new MyObject('this','that')
    }

    def 'should put and remove and item' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()

        when:
        store.put(key, new MyObject('this','that'), Duration.ofSeconds(10))
        then:
        store.get(key) == new MyObject('this','that')

        when:
        store.remove(key)
        then:
        store.get(key) == null
    }

    def 'should put if absent' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()

        when:
        def done = store.putIfAbsent(key, new MyObject('this','that'))
        then:
        done
        and:
        store.get(key) == new MyObject('this','that')

        when:
        done = store.putIfAbsent(key, new MyObject('xx','yy'))
        then:
        !done
        and:
        store.get(key) == new MyObject('this','that')

        when:
        sleep ttlMillis*2
        done = store.putIfAbsent(key, new MyObject('xx','yy'))
        then:
        done
        and:
        store.get(key) == new MyObject('xx','yy')

    }

    def 'should put if absent with custom ttl' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()

        when:
        def done = store.putIfAbsent(key, new MyObject('this','that'), Duration.ofSeconds(10))
        then:
        done
        and:
        store.get(key) == new MyObject('this','that')

        when:
        done = store.putIfAbsent(key, new MyObject('xx','yy'))
        then:
        !done
        and:
        store.get(key) == new MyObject('this','that')

        when:
        sleep ttlMillis*2
        done = store.putIfAbsent(key, new MyObject('xx','yy'))
        then:
        !done
        and:
        store.get(key) == new MyObject('this','that')

    }


    def 'should put and get value by record id' () {
        given:
        def store = new MyCacheStore(provider)
        def recId = UUID.randomUUID().toString()
        def key =  UUID.randomUUID().toString()

        expect:
        store.get(key) == null
        store.findByRequestId(recId) == null

        when:
        def value = new MyState(recId, 'value')
        store.put(key, value)
        then:
        store.get(key) == value
        store.findByRequestId(recId) == value
        and:
        store.get(recId) == null
        store.findByRequestId(key) == null
    }


    def 'should put and get value by record id if absent' () {
        given:
        def store = new MyCacheStore(provider)
        def recId = UUID.randomUUID().toString()
        def key =  UUID.randomUUID().toString()

        expect:
        store.get(key) == null
        store.findByRequestId(recId) == null

        when:
        def value = new MyState(recId, 'value')
        def done = store.putIfAbsent(key, value)
        then:
        done
        and:
        store.get(key) == value
        store.findByRequestId(recId) == value
        and:
        store.get(recId) == null
        store.findByRequestId(key) == null

        when:
        done = store.putIfAbsent(key, new MyState('xx', 'yy'))
        then:
        !done
        and:
        store.get(key) == value
        store.findByRequestId(recId) == value
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

    static class NotJsonStore extends AbstractStateStore<MyObject> {

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

    static class NonDeterministicStore extends AbstractStateStore<MyObject> {

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
        store.get(key).version() == 1
        and: 'the adopted entry is framed'
        provider.get(store.key0(key)).startsWith('{"@v":1,')
    }

    def 'should reject a value type that does not implement Versioned' () {
        given:
        def store = new PlainStore(provider)
        store.put('k1', new PlainObject('a'))

        when:
        store.replaceIf('k1', new PlainObject('b'))
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
        def done = store.replaceIf(key, new MyState(recId, 'value'))
        then:
        done
        and:
        store.get(key).version() == 1
        store.findByRequestId(recId) == new MyState(recId, 'value').withVersion(1)
    }

}
