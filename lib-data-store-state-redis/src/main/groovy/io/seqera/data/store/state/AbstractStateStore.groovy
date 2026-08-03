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

    /**
     * Replace the value associated with the specified key only if the entry has not been
     * written since the caller read it (compare-and-swap), preserving the entry's
     * remaining time-to-live.
     *
     * <p>The write witness is the value's own {@link Versioned#version()} — the version
     * of the read the value was derived from: the replace lands only when the stored
     * version still equals it, and the value is persisted with the version incremented.
     * Atomicity between the version check and the write is guaranteed by comparing the
     * stored form exactly as read — never a re-serialization — so no assumption is made
     * on the encoding strategy being deterministic.
     *
     * <p>An entry written before versioning existed reports version {@code 0} and is
     * adopted by its first successful replace. Unconditional {@link #put} writes do not
     * move the version, so they must be reserved for entry creation.
     *
     * @param key The key of the entry to be replaced
     * @param value The new value, carrying the version of the read it was derived from;
     *        its type must implement {@link Versioned}
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *         exist or the entry was written since the version carried by the value
     */
    boolean replaceIf(String key, V value) {
        return replaceIf0(key, value, null)
    }

    /**
     * Same as {@link #replaceIf(String, Object)}, resetting the entry time-to-live to
     * the specified duration.
     *
     * @param key The key of the entry to be replaced
     * @param value The new value, carrying the version of the read it was derived from;
     *        its type must implement {@link Versioned}
     * @param ttl The new max time-to-live of the entry once replaced
     * @return {@code true} if the value was replaced, {@code false} if the key does not
     *         exist or the entry was written since the version carried by the value
     */
    boolean replaceIf(String key, V value, Duration ttl) {
        return replaceIf0(key, value, ttl)
    }

    private boolean replaceIf0(String key, V value, Duration ttl) {
        if( !(value instanceof Versioned) )
            throw new IllegalArgumentException("Versioned compare-and-swap requires the value type to implement Versioned - offending type: ${value.getClass().getName()}")
        final k = key0(key)
        final raw = delegate.get(k)
        if( raw == null )
            return false
        final Versioned current = (Versioned) deserialize(raw)
        final long expected = ((Versioned) value).version()
        // the caller's read basis is stale: the entry was written since
        if( current.version() != expected )
            return false
        final V next = (V) ((Versioned) value).withVersion(expected + 1)
        // the raw form as read makes the check-and-write atomic: a write landing after
        // the read above changes the stored bytes and refuses the swap, whatever it wrote
        final done = ttl != null
                ? delegate.replaceIf(k, raw, serialize(next), ttl)
                : delegate.replaceIf(k, raw, serialize(next))
        if( done && next instanceof RequestIdAware ) {
            delegate.put(requestId0(((RequestIdAware) next).getRequestId()), key, ttl != null ? ttl : getDuration())
        }
        return done
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
