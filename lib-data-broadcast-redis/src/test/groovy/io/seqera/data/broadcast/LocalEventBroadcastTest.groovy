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

import io.seqera.data.broadcast.impl.LocalEventBroadcast
import spock.lang.Specification

class LocalEventBroadcastTest extends Specification {

    def broadcast = new LocalEventBroadcast<String>()

    def 'should buffer events and deliver to late-connecting clients'() {
        given:
        broadcast.offer('k1', 'event-1')
        broadcast.offer('k1', 'event-2')
        broadcast.offer('k1', 'event-3')

        when:
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { received << it }

        then:
        received == ['event-1', 'event-2', 'event-3']
    }

    def 'should push new events to connected clients'() {
        given:
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { received << it }

        when:
        broadcast.offer('k1', 'live')

        then:
        received == ['live']
    }

    def 'should deliver events to multiple clients'() {
        given:
        def r1 = new CopyOnWriteArrayList<>()
        def r2 = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { r1 << it }
        broadcast.registerClient('k1', 'c2') { r2 << it }

        when:
        broadcast.offer('k1', 'shared')

        then:
        r1 == ['shared']
        r2 == ['shared']
    }

    def 'should stop delivering after unregister'() {
        given:
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { received << it }

        when:
        broadcast.unregisterClient('k1', 'c1')
        broadcast.offer('k1', 'missed')

        then:
        received.isEmpty()
    }

    def 'should return buffered events'() {
        given:
        broadcast.offer('k1', 'a')
        broadcast.offer('k1', 'b')

        expect:
        broadcast.getBufferedEvents('k1') == ['a', 'b']
        broadcast.getBufferedEvents('missing').isEmpty()
    }

    def 'should cleanup buffers and clients'() {
        given:
        broadcast.offer('k1', 'data')
        broadcast.registerClient('k1', 'c1') { }

        when:
        broadcast.cleanup('k1')

        then:
        broadcast.getBufferedEvents('k1').isEmpty()
    }

    def 'should deliver buffered and live events in order'() {
        given:
        broadcast.offer('k1', 'buf-1')
        broadcast.offer('k1', 'buf-2')
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { received << it }

        when:
        broadcast.offer('k1', 'live-1')
        broadcast.offer('k1', 'live-2')

        then:
        received == ['buf-1', 'buf-2', 'live-1', 'live-2']
    }

    def 'should not duplicate events between replay and live delivery'() {
        given:
        broadcast.offer('k1', 'before')
        def received = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { received << it }

        when:
        broadcast.offer('k1', 'after')

        then:
        received == ['before', 'after']
    }

    def 'should isolate events between different keys'() {
        given:
        def r1 = new CopyOnWriteArrayList<>()
        def r2 = new CopyOnWriteArrayList<>()
        broadcast.registerClient('k1', 'c1') { r1 << it }
        broadcast.registerClient('k2', 'c2') { r2 << it }

        when:
        broadcast.offer('k1', 'for-k1')
        broadcast.offer('k2', 'for-k2')

        then:
        r1 == ['for-k1']
        r2 == ['for-k2']
    }
}
