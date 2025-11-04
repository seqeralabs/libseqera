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

package io.seqera.data.store.state.impl

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import groovy.transform.CompileStatic
import io.micronaut.context.annotation.Requires
import io.seqera.data.store.state.CountParams
import io.seqera.data.store.state.CountResult
import jakarta.inject.Singleton
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

/**
 * Simple cache store implementation for development purpose
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(missingProperty = 'redis.uri')
@Singleton
@CompileStatic
class LocalStateProvider implements StateProvider<String,String> {

    private static class Entry<V> {
        final V value
        final Duration ttl
        final Instant ts

        Entry(V value, Duration ttl=null) {
            this.value = value
            this.ttl = ttl
            this.ts = Instant.now()
        }

        boolean isExpired() {
            return ttl!=null ? ts.plus(ttl) <= Instant.now() : false
        }
    }

    private Map<String,Entry<String>> store = new ConcurrentHashMap<>()

    private Map<String, AtomicInteger> counters = new ConcurrentHashMap<>()

    @Override
    String get(String key) {
        final entry = store.get(key)
        if( !entry ) {
            return null
        }
        if( entry.isExpired() ) {
            store.remove(key)
            return null
        }
        return entry.value
    }

    @Override
    void put(String key, String value) {
        store.put(key, new Entry<>(value,null))
    }

    @Override
    void put(String key, String value, Duration ttl) {
        store.put(key, new Entry<>(value,ttl))
    }

    @Override
    boolean putIfAbsent(String key, String value) {
        return putIfAbsent0(key, value, null) == null
    }

    @Override
    boolean putIfAbsent(String key, String value, Duration ttl) {
        return putIfAbsent0(key, value, ttl) == null
    }

    @Override
    synchronized CountResult<String> putJsonIfAbsentAndIncreaseCount(String key, String json, Duration ttl, CountParams counterKey, String luaScript) {
        final counter = counterKey.key + '/' + counterKey.field
        final done = putIfAbsent0(key, json, ttl) == null
        final addr = counters
                .computeIfAbsent(counter, (it)-> new AtomicInteger())
        if( done ) {
            final count = addr.incrementAndGet()
            // apply the conversion
            Globals globals = JsePlatform.standardGlobals()
            globals.set('value', LuaValue.valueOf(json))
            globals.set('counter_value', LuaValue.valueOf(count))
            LuaValue chunk = globals.load("return $luaScript;");
            LuaValue result = chunk.call();
            // store the result
            put(key, result.toString(), ttl)
            return new CountResult<String>(true, result.toString(), count)
        }
        else
            return new CountResult<String>(false, get(key), addr.get())
    }

    private String putIfAbsent0(String key, String value, Duration ttl) {
        final entry = store.get(key)
        if( entry?.isExpired() )
            store.remove(key)
        return store.putIfAbsent(key, new Entry<>(value,ttl))?.value
    }

    @Override
    void remove(String key) {
        store.remove(key)
    }

    @Override
    void clear() {
        store.clear()
    }

}
