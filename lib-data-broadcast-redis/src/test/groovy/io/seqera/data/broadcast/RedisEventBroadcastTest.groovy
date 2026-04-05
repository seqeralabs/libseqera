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

package io.seqera.data.broadcast

import java.util.concurrent.CopyOnWriteArrayList

import io.seqera.data.broadcast.impl.RedisEventBroadcast
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.serde.encode.StringEncodingStrategy
import redis.clients.jedis.JedisPool
import spock.lang.Shared
import spock.lang.Specification

class RedisEventBroadcastTest extends Specification implements RedisTestContainer {

    static final StringEncodingStrategy<String> IDENTITY = new StringEncodingStrategy<String>() {
        @Override String encode(String value) { value }
        @Override String decode(String value) { value }
    }

    @Shared JedisPool jedisPool
    @Shared RedisEventBroadcast<String> broadcast

    def setup() {
        if (jedisPool == null) {
            jedisPool = new JedisPool(redisHostName, Integer.parseInt(redisPort))
            broadcast = new RedisEventBroadcast<>(jedisPool, 'test:broadcast:', IDENTITY)
        }
    }

    def 'should buffer events and deliver to late-connecting clients'() {
        given:
        def key = 'buf-' + UUID.randomUUID()
        broadcast.offer(key, 'event-1')
        broadcast.offer(key, 'event-2')
        broadcast.offer(key, 'event-3')

        when:
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient(key, 'c1') { received << it }

        then:
        received == ['event-1', 'event-2', 'event-3']

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should push new events to connected clients'() {
        given:
        def key = 'push-' + UUID.randomUUID()
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient(key, 'c1') { received << it }

        when:
        broadcast.offer(key, 'live')

        then:
        received == ['live']

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should deliver events to multiple clients'() {
        given:
        def key = 'multi-' + UUID.randomUUID()
        def r1 = new CopyOnWriteArrayList<>()
        def r2 = new CopyOnWriteArrayList<>()
        broadcast.registerClient(key, 'c1') { r1 << it }
        broadcast.registerClient(key, 'c2') { r2 << it }

        when:
        broadcast.offer(key, 'shared')

        then:
        r1 == ['shared']
        r2 == ['shared']

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should stop delivering after unregister'() {
        given:
        def key = 'unreg-' + UUID.randomUUID()
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient(key, 'c1') { received << it }

        when:
        broadcast.unregisterClient(key, 'c1')
        broadcast.offer(key, 'missed')

        then:
        received.isEmpty()

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should return buffered events'() {
        given:
        def key = 'get-' + UUID.randomUUID()
        broadcast.offer(key, 'a')
        broadcast.offer(key, 'b')

        expect:
        broadcast.getBufferedEvents(key) == ['a', 'b']
        broadcast.getBufferedEvents('missing-' + UUID.randomUUID()).isEmpty()

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should cleanup buffers and clients'() {
        given:
        def key = 'clean-' + UUID.randomUUID()
        broadcast.offer(key, 'data')
        broadcast.registerClient(key, 'c1') { }

        when:
        broadcast.cleanup(key)

        then:
        broadcast.getBufferedEvents(key).isEmpty()
    }

    def 'should deliver buffered and live events in order'() {
        given:
        def key = 'order-' + UUID.randomUUID()
        broadcast.offer(key, 'buf-1')
        broadcast.offer(key, 'buf-2')
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient(key, 'c1') { received << it }

        when:
        broadcast.offer(key, 'live-1')
        broadcast.offer(key, 'live-2')

        then:
        received == ['buf-1', 'buf-2', 'live-1', 'live-2']

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should not duplicate events between replay and live delivery'() {
        given:
        def key = 'nodup-' + UUID.randomUUID()
        broadcast.offer(key, 'before')
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient(key, 'c1') { received << it }

        when:
        broadcast.offer(key, 'after')

        then:
        received == ['before', 'after']

        cleanup:
        broadcast.cleanup(key)
    }

    def 'should isolate events between different keys'() {
        given:
        def k1 = 'iso-a-' + UUID.randomUUID()
        def k2 = 'iso-b-' + UUID.randomUUID()
        def r1 = new CopyOnWriteArrayList<>()
        def r2 = new CopyOnWriteArrayList<>()
        broadcast.registerClient(k1, 'c1') { r1 << it }
        broadcast.registerClient(k2, 'c2') { r2 << it }

        when:
        broadcast.offer(k1, 'for-k1')
        broadcast.offer(k2, 'for-k2')

        then:
        r1 == ['for-k1']
        r2 == ['for-k2']

        cleanup:
        broadcast.cleanup(k1)
        broadcast.cleanup(k2)
    }
}
