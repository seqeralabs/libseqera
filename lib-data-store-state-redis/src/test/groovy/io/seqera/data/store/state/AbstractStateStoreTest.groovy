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
    static class MyObject {
        String field1
        String field2
    }

    static class MyState extends MyObject implements RequestIdAware {

        MyState(String field1, String field2) {
            super(field1, field2)
        }

        @Override
        String getRequestId() {
            return field1
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
     * A non-versioned type whose serialized form legitimately begins with a {@code @v}
     * property — byte-wise indistinguishable from a version frame. The store must not
     * mistake it for one: it only frames {@link VersionAware} values.
     */
    @Canonical
    static class AtVObject {
        Long atV
        String field1
    }

    static class AtVEncoder implements StringEncodingStrategy<AtVObject> {

        @Override
        String encode(AtVObject value) {
            return value.field1 != null
                    ? '{"@v":' + value.atV + ',"field1":"' + value.field1 + '"}'
                    : '{"@v":' + value.atV + '}'
        }

        @Override
        AtVObject decode(String encoded) {
            final v = (encoded =~ /"@v":(\d+)/)
            final f1 = (encoded =~ /"field1":"([^"]*)"/)
            return new AtVObject(
                    v.find() ? v.group(1) as Long : null,
                    f1.find() ? f1.group(1) : null )
        }
    }

    static class AtVStore extends AbstractStateStore<AtVObject> {

        AtVStore(StateProvider<String, String> provider) {
            super(provider, new AtVEncoder())
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

    def 'should not strip a frame-looking property from a non-versioned value' () {
        given:
        def store = new AtVStore(provider)
        def key = UUID.randomUUID().toString()

        when: 'a non-versioned value whose first serialized property is literally @v'
        store.put(key, new AtVObject(5L, 'a'))
        then: 'the stored form carries the property as plain user data - no frame is added'
        provider.get(store.key0(key)) == '{"@v":5,"field1":"a"}'
        and: 'the read gives the property back instead of swallowing it as a frame'
        store.get(key) == new AtVObject(5L, 'a')
    }

    def 'should not decode a frame-looking non-versioned entry to an empty object' () {
        given:
        def store = new AtVStore(provider)
        def key = UUID.randomUUID().toString()
        and: 'a foreign entry that is exactly what an empty-payload frame looks like'
        provider.put(store.key0(key), '{"@v":5}', Duration.ofSeconds(10))

        expect: 'the property is user data, not a frame around an empty object'
        store.get(key) == new AtVObject(5L, null)
    }

}
