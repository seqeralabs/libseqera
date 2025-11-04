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
import java.util.concurrent.TimeoutException

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.activator.redis.RedisActivator
import io.seqera.data.store.future.FutureHash
import jakarta.inject.Inject
import jakarta.inject.Singleton
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams

/**
 * Implements a future queue using Redis hash. The hash was chosen over
 * a Redis list, because values that fail to be collected within the
 * expected timeout, are evicted by Redis by simply specifying the hash expiration.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(bean = RedisActivator)
@Singleton
@CompileStatic
class RedisFutureHash implements FutureHash<String>  {

    @Inject
    private JedisPool pool

    @Override
    void put(String key, String value, Duration expiration) {
        try (Jedis conn = pool.getResource()) {
            final params = new SetParams().px(expiration.toMillis())
            conn.set(key, value, params)
        }
    }

    @Override
    String take(String key) throws TimeoutException {
        try (Jedis conn = pool.getResource()) {
            /*
             * get and remove the value using an atomic operation
             */
            final tx = conn.multi()
            final result = tx.get(key)
            tx.del(key)
            tx.exec()
            return result.get()
        }
    }

}
