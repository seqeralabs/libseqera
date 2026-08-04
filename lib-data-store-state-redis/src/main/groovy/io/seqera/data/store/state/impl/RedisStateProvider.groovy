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

package io.seqera.data.store.state.impl

import java.time.Duration

import groovy.transform.CompileStatic
import io.micronaut.context.annotation.Requires
import io.seqera.activator.redis.RedisActivator
import io.seqera.data.store.state.CountParams
import io.seqera.data.store.state.CountResult
import jakarta.inject.Inject
import jakarta.inject.Singleton
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
/**
 * Redis based implementation for a {@link StateProvider}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(bean = RedisActivator)
@Singleton
@CompileStatic
class RedisStateProvider implements StateProvider<String,String>, VersionProvider<String,String> {

    @Inject
    private JedisPool pool

    @Override
    String get(String key) {
        try( Jedis conn=pool.getResource() ) {
            return conn.get(key)
        }
    }

    @Override
    void put(String key, String value) {
        put(key, value, null)
    }

    @Override
    void put(String key, String value, Duration ttl) {
        try( Jedis conn=pool.getResource() ) {
            final params = new SetParams()
            if( ttl )
                params.px(ttl.toMillis())
            conn.set(key, value, params)
        }
    }

    @Override
    boolean putIfAbsent(String key, String value) {
        putIfAbsent(key, value, null)
    }

    @Override
    boolean putIfAbsent(String key, String value, Duration ttl) {
        try( Jedis conn=pool.getResource() ) {
            final params = new SetParams().nx()
            if( ttl )
                params.px(ttl.toMillis())
            final result = conn.set(key, value, params)
            return result == 'OK'
        }
    }

    /*
     * Versioned compare-and-swap: replace the value at KEYS[1] only if the version carried
     * by its leading {"@v":N frame equals ARGV[1], resetting the TTL to ARGV[3] millis.
     * An unframed value counts as version 0; a missing key never matches. Only the head
     * of the stored value is inspected (GETRANGE), so the cost is independent of the
     * payload size.
     *
     * The digits are compared literally against the canonical decimal form of the
     * expected version - the only form a store-written frame can carry - so foreign
     * data whose head merely resembles a frame never matches and is left untouched:
     * a digit run whose terminator falls outside the 28-byte peek (impossible for a
     * store-written frame, whose head is at most 26 bytes) is refused outright rather
     * than falling through to unframed. LocalStateProvider mirrors the same comparison.
     */
    static private final String REPLACE_IF = '''
        local head = redis.call('GETRANGE', KEYS[1], 0, 27)
        if head == '' then return 0 end
        local ver = string.match(head, '^{"@v":(%d+)[,}]')
        if not ver and string.match(head, '^{"@v":%d') then return 0 end
        if (ver or '0') ~= ARGV[1] then return 0 end
        redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
        return 1
        '''

    @Override
    boolean replaceIf(String key, long expected, String value, Duration ttl) {
        try( Jedis conn=pool.getResource() ) {
            return conn.eval(REPLACE_IF, 1, key, String.valueOf(expected), value, ttl.toMillis().toString()) == 1L
        }
    }

    /*
     * Set a value only the specified key does not exists, if the value can be set
     * the counter identified by the key provided via 'KEYS[2]' is incremented by 1,
     *
     * If the key already exists return the current key value.
     */
    static private String putAndIncrement(String luaScript) {
        """
        local value = ARGV[1]
        local ttl = ARGV[2]
        local pattern = ARGV[3]  
        if redis.call('EXISTS', KEYS[1]) == 0 then
            -- increment the counter 
            local counter_value = redis.call('HINCRBY', KEYS[2], KEYS[3], 1)
            value = ${luaScript}
            redis.call('SET', KEYS[1], value, 'PX', ttl)
            -- return the updated value
            return {1, value, counter_value} 
        else
            return {0, redis.call('GET', KEYS[1]), redis.call('HGET', KEYS[2], KEYS[3])}
        end
        """
    }


    CountResult<String> putJsonIfAbsentAndIncreaseCount(String key, String json, Duration ttl, String counter, String luaScript) {
        return putJsonIfAbsentAndIncreaseCount(key, json, ttl, CountParams.of(counter), luaScript)
    }

    @Override
    CountResult<String> putJsonIfAbsentAndIncreaseCount(String key, String json, Duration ttl, CountParams counter, String mapping) {
        try( Jedis jedis=pool.getResource() )  {
            final result = jedis.eval(putAndIncrement(mapping), 3, key, counter.key, counter.field, json, ttl.toMillis().toString())
            return new CountResult<>(
                    (result as List)[0] == 1,
                    (result as List)[1] as String,
                    (result as List)[2] as Integer)
        }
    }

    @Override
    void remove(String key) {
        try( Jedis conn=pool.getResource() ) {
            conn.del(key)
        }
    }

    @Override
    void clear() {
        try( Jedis conn=pool.getResource() ) {
            conn.flushAll()
        }
    }

}
