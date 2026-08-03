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
    }

    def 'should replace a value only when the current one matches' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a','b'))

        expect: 'a mismatching expected value is not replaced'
        !store.replaceIf(key, new MyObject('x','y'), new MyObject('c','d'))
        store.get(key) == new MyObject('a','b')

        and: 'a matching expected value is replaced'
        store.replaceIf(key, new MyObject('a','b'), new MyObject('c','d'))
        store.get(key) == new MyObject('c','d')
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
     * replicas whose JVMs serialize the same value with different bytes (field order,
     * hash-based collection ordering). A store using it can never reproduce the raw form
     * it previously wrote, so any CAS comparing a re-serialization is bound to fail.
     */
    static class NonDeterministicEncoder implements StringEncodingStrategy<MyObject> {

        private final AtomicInteger nonce = new AtomicInteger()

        @Override
        String encode(MyObject value) {
            return "${value.field1}|${value.field2}|${nonce.incrementAndGet()}"
        }

        @Override
        MyObject decode(String encoded) {
            final parts = encoded.tokenize('|')
            return new MyObject(parts[0], parts[1])
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

    def 'should converge update even when the encoding is not deterministic' () {
        given:
        def store = new NonDeterministicStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'))

        expect:
        store.update(key, { MyObject v -> new MyObject(v.field1, 'updated') }, 5)
        store.get(key) == new MyObject('a', 'updated')
    }

    def 'should return false when updating a missing key' () {
        given:
        def store = new MyCacheStore(provider)

        expect:
        !store.update(UUID.randomUUID().toString(), { MyObject v -> v }, 5)
    }

    def 'should abort update when the mutator returns null' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'))

        expect:
        !store.update(key, { MyObject v -> null }, 5)
        store.get(key) == new MyObject('a', 'b')
    }

    def 'should skip the write when the mutator returns the same instance' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'))

        expect:
        store.update(key, { MyObject v -> v }, 5)
        store.get(key) == new MyObject('a', 'b')
    }

    def 'should re-read and retry when a concurrent writer gets in between' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', '1'))
        and: 'a mutator that simulates a concurrent write on its first invocation'
        def first = true
        def mutator = { MyObject v ->
            if( first ) {
                first = false
                store.put(key, new MyObject('a', '2'))
            }
            return new MyObject(v.field1, v.field2 + '-x')
        }

        expect: 'the update lands on the value written by the concurrent writer'
        store.update(key, mutator, 5)
        store.get(key) == new MyObject('a', '2-x')
    }

    def 'should give up after exhausting the attempts under persistent contention' () {
        given:
        def store = new MyCacheStore(provider)
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', '0'))
        and: 'a mutator that always invalidates its own read'
        def count = 0
        def mutator = { MyObject v ->
            count += 1
            store.put(key, new MyObject('a', "clobber-$count".toString()))
            return new MyObject(v.field1, 'mine')
        }

        expect:
        !store.update(key, mutator, 3)
        count == 3
    }

    def 'should record the request-id mapping when updating to a RequestIdAware value' () {
        given:
        def store = new MyCacheStore(provider)
        def recId = UUID.randomUUID().toString()
        def key = UUID.randomUUID().toString()
        store.put(key, new MyObject('a', 'b'))

        when:
        def done = store.update(key, { MyObject v -> new MyState(recId, 'value') }, 5)
        then:
        done
        store.findByRequestId(recId) == new MyState(recId, 'value')
    }

    def 'should reject a non-positive attempts bound' () {
        given:
        def store = new MyCacheStore(provider)

        when:
        store.update('any', { MyObject v -> v }, 0)
        then:
        thrown(IllegalArgumentException)
    }

}
