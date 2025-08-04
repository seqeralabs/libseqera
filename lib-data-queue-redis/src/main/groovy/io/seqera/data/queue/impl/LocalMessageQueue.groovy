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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.activator.redis.RedisActivator
import io.seqera.data.queue.MessageQueue
import jakarta.inject.Singleton
/**
 * Implement a message broker based on a simple blocking queue.
 * This is only meant for local/development purposes
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(missingBeans = RedisActivator)
@Singleton
@CompileStatic
class LocalMessageQueue implements MessageQueue<String> {

    private ConcurrentHashMap<String, LinkedBlockingQueue<String>> store = new ConcurrentHashMap<>()

    /**
     * {@inheritDoc}
     */
    @Override
    void offer(String target, String message) {
        store
            .computeIfAbsent(target, (it)->new LinkedBlockingQueue<String>())
            .offer(message)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    String poll(String target) {
        store
            .computeIfAbsent(target, (it)->new LinkedBlockingQueue<String>())
            .poll()
    }

    /**
     * {@inheritDoc}
     */
    String poll(String target, Duration timeout) {
        final q =  store .computeIfAbsent(target, (it)->new LinkedBlockingQueue<String>())
        final millis = timeout.toMillis()
        return millis>0
                ? q.poll(millis, TimeUnit.MILLISECONDS)
                : q.take()
    }

    /**
     * {@inheritDoc}
     */
    int length(String target) {
        store.get(target)?.size() ?: 0
    }
}
