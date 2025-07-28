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

package io.seqera.data.queue.impl

import java.time.Duration

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.data.queue.MessageQueue
import jakarta.inject.Inject
import jakarta.inject.Singleton
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
/**
 * Implements a message broker using Redis list
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(env = 'redis')
@Singleton
@CompileStatic
class RedisMessageQueue implements MessageQueue<String>  {

    @Inject
    private JedisPool pool

    /**
     * {@inheritDoc}
     */
    @Override
    void offer(String target, String message) {
        try (Jedis conn = pool.getResource()) {
            conn.lpush(target, message)
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    String poll(String target) {
        try (Jedis conn = pool.getResource()) {
            return conn.rpop(target)
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    String poll(String target, Duration duration) {
        try (Jedis conn = pool.getResource()) {
            double d = duration.toMillis() / 1000.0
            final entry = conn.brpop(d, target)
            return entry ? entry.getValue() : null
        }
    }

    /**
     * {@inheritDoc}
     */
    int length(String target) {
        try (Jedis conn = pool.getResource()) {
            return conn.llen(target)
        }
    }
}
