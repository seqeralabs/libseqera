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

package io.seqera.data.store.state

import java.time.Duration

import groovy.transform.CompileStatic
import io.seqera.serde.encode.StringEncodingStrategy
import io.seqera.data.store.state.impl.StateProvider
/**
 * Implements a generic store for ephemeral state data
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
abstract class AbstractStateStore<V> implements StateStore<String,V> {

    private StringEncodingStrategy<V> encodingStrategy

    private StateProvider<String,String> delegate

    AbstractStateStore(StateProvider<String,String> provider, StringEncodingStrategy<V> encodingStrategy) {
        this.delegate = provider
        this.encodingStrategy = encodingStrategy
    }

    protected abstract String getPrefix()

    protected abstract Duration getDuration()

    protected String key0(String k) { return getPrefix() + ':' + k  }

    protected String requestId0(String requestId) {
        if( !requestId )
            throw new IllegalStateException("Argument 'requestId' cannot be null")
        return getPrefix() + '/request-id:' + requestId
    }

    /**
     * Defines the counter for auto-increment operations. By default
     * uses the entry "key". Subclasses can provide a custom logic to use a
     * different counter key.
     *
     * @param key
     *      The entry for which the increment should be performed
     * @param value
     *      The entry value for which the increment should be performed
     * @return
     *      The counter key that by default is the entry key.
     */
    protected CountParams counterKey(String key, V value) {
        assert key, "Argument 'key' cannot be empty"
        return new CountParams(getPrefix() + '/counter', key)
    }

    /**
     * Defines the Lua script that's applied to increment the entry counter.
     *
     * It assumes the entry is serialised as JSON object and it contains a {@code count} attribute
     * that will be update with the store counter value.
     *
     * @return The Lua script used to increment the entry count.
     */
    protected String counterScript() {
        // NOTE:
        // "value" is expected to be a Lua variable holding the JSON object
        // "counter_value" is expected to be a Lua variable holding the new count value
        /string.gsub(value, '"count"%s*:%s*(%d+)', '"count":' .. counter_value)/
    }

    protected V deserialize(String encoded) {
        return encodingStrategy.decode(encoded)
    }

    protected String serialize(V value) {
        return encodingStrategy.encode(value)
    }

    @Override
    V get(String key) {
        final result = delegate.get(key0(key))
        return result ? deserialize(result) : null
    }

    V findByRequestId(String requestId) {
        final key = delegate.get(requestId0(requestId))
        return get(key)
    }

    @Override
    void put(String key, V value) {
        put(key, value, getDuration())
    }

    @Override
    void put(String key, V value, Duration ttl) {
        delegate.put(key0(key), serialize(value), ttl)
        if( value instanceof RequestIdAware ) {
            delegate.put(requestId0(value.getRequestId()), key, ttl)
        }
    }

    @Override
    boolean putIfAbsent(String key, V value, Duration ttl) {
        final result = delegate.putIfAbsent(key0(key), serialize(value), ttl)
        if( result && value instanceof RequestIdAware ) {
            delegate.put(requestId0(value.getRequestId()), key, ttl)
        }
        return result
    }

    @Override
    boolean putIfAbsent(String key, V value) {
        return putIfAbsent(key, value, getDuration())
    }

    CountResult<V> putIfAbsentAndCount(String key, V value) {
        putIfAbsentAndCount(key, value, getDuration())
    }

    CountResult<V> putIfAbsentAndCount(String key, V value, Duration ttl) {
        final result = delegate.putJsonIfAbsentAndIncreaseCount(
                key0(key),
                serialize(value),
                ttl,
                counterKey(key,value),
                counterScript())
        // update the `value` with the result one
        final updated = deserialize(result.value)
        if( result && updated instanceof RequestIdAware ) {
            delegate.put(requestId0(updated.getRequestId()), key, ttl)
        }
        return new CountResult<V>( result.succeed, updated, result.count)
    }

    @Override
    void remove(String key) {
        delegate.remove(key0(key))
    }

    @Override
    void clear() {
        delegate.clear()
    }

}
