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

package io.seqera.service.pairing.socket

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnMessage
import io.seqera.service.pairing.PairingConfig
import io.seqera.service.pairing.PairingRecord
import io.seqera.service.pairing.PairingService
import io.seqera.service.pairing.socket.msg.PairingHeartbeat
import io.seqera.service.pairing.socket.msg.PairingMessage
import spock.lang.Specification

class PairingWebSocketTest extends Specification {

    def 'should declare an explicit max payload length above the Micronaut default' () {
        given:
        def method = PairingWebSocket.getDeclaredMethod('onMessage', String, String, String, PairingMessage, WebSocketSession)

        when:
        def annotation = method.getAnnotation(OnMessage)

        then:
        annotation.maxPayloadLength() == PairingWebSocket.MAX_PAYLOAD_LENGTH
        and:
        PairingWebSocket.MAX_PAYLOAD_LENGTH == 4 * 1024 * 1024
    }

    def 'should close sessions with no heartbeat within the heartbeat timeout' () {
        given:
        def channel = Mock(PairingChannel)
        def pairing = Mock(PairingService) { getPairingRecord('tower', 'https://foo.com') >> new PairingRecord() }
        def config = Mock(PairingConfig) { getHeartbeatTimeout() >> Duration.ofSeconds(120) }
        def socket = new PairingWebSocket(channel: channel, pairingService: pairing, config: config)
        and:
        def beating = Mock(WebSocketSession) { getId() >> 'sess-1'; sendAsync(_) >> CompletableFuture.completedFuture(null) }
        def silent = Mock(WebSocketSession) { getId() >> 'sess-2' }

        when: 'only the first session sends a heartbeat'
        socket.onMessage('tower', 'tk', 'https://foo.com', new PairingHeartbeat(msgId: 'x'), beating)
        and: 'the sweep runs before the timeout'
        socket.closeSilentSessions(Instant.now().plusSeconds(60))
        then:
        0 * channel.unregisterClient(_, _, _)
        0 * beating.close(_)

        when: 'the sweep runs after the timeout'
        socket.closeSilentSessions(Instant.now().plusSeconds(121))
        then: 'the silent session is unregistered and closed, the one that never sent a heartbeat is left alone'
        1 * channel.unregisterClient('tower', 'https://foo.com', 'sess-1')
        1 * beating.close(CloseReason.GOING_AWAY)
        0 * silent.close(_)

        when: 'the sweep runs again'
        socket.closeSilentSessions(Instant.now().plusSeconds(240))
        then: 'the session is not closed twice'
        0 * channel.unregisterClient(_, _, _)
        0 * beating.close(_)
    }
}
