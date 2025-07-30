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

package io.seqera.data.stream.impl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.data.stream.MessageConsumer
import io.seqera.data.stream.MessageStream
import jakarta.inject.Singleton
/**
 * Implement a {@link MessageStream} using a Java {@link java.util.concurrent.BlockingQueue}.
 * This is only meant for developing purpose.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(missingProperty = 'redis.uri')
@Singleton
@CompileStatic
class LocalMessageStream implements MessageStream<String> {

    private ConcurrentHashMap<String, ConcurrentHashMap<String, LinkedBlockingQueue<String>>> delegate = new ConcurrentHashMap<>()

    /**
     * {@inheritDoc}
     */
    @Override
    void init(String streamId, String groupId) {
        delegate.putIfAbsent(streamId, new ConcurrentHashMap<>())
        delegate.get(streamId).putIfAbsent(groupId, new LinkedBlockingQueue<>())
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void offer(String streamId, String message) {
        delegate
                .get(streamId)
                .forEachEntry(1, linkedBlockingQueueEntry -> linkedBlockingQueueEntry.getValue().offer(message))
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean consume(String streamId, String groupId, MessageConsumer<String> consumer) {
        final message = delegate
                .get(streamId)
                .get(groupId)
                .poll()
        if( message==null ) {
            return false
        }

        def result = false
        try {
            result = consumer.accept(message)
        }
        catch (Throwable e) {
            result = false
            throw e
        }
        finally {
            if( !result ) {
                // add again message not consumed to mimic the behavior or redis stream
                sleep(1_000)
                delegate
                        .get(streamId)
                        .get(groupId)
                        .offer(message)
            }
            return result
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int length(String streamId) {
        def streamGroups = delegate.get(streamId)
        if (streamGroups == null || streamGroups.isEmpty()) {
            return 0
        }
        // Return the size of the first consumer group queue (they should all have the same size)
        return streamGroups.values().iterator().next().size()
    }
}
