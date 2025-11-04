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

package io.seqera.data.store.future.impl

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.activator.redis.RedisActivator
import io.seqera.data.store.future.FutureHash
import jakarta.inject.Singleton
/**
 * Implement a future queue based on a simple hash map.
 * This is only meant for local/development purposes
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(missingBeans = RedisActivator)
@Singleton
@CompileStatic
class LocalFutureHash implements FutureHash<String> {

    private ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>()

    @Override
    void put(String key, String value, Duration expiration) {
        store.putIfAbsent(key, value)
    }

    @Override
    String take(String key) {
        return store.remove(key)
    }
}
