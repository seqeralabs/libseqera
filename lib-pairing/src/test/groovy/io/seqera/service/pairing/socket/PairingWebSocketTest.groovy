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

import io.micronaut.websocket.CloseReason
import io.micronaut.websocket.WebSocketSession
import io.micronaut.websocket.annotation.OnMessage
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

    def 'should close the session on error' () {
        given:
        def socket = new PairingWebSocket()
        def session = Mock(WebSocketSession)

        when:
        socket.onError('tower', 'token', 'http://foo.com', session, ERROR)

        then:
        _ * session.getId() >> 'session-1'
        1 * session.isOpen() >> true
        1 * session.close(CloseReason.INTERNAL_ERROR)

        where:
        ERROR                                   | _
        new IOException('No route to host')     | _
        new IllegalStateException('Unexpected') | _
    }

    def 'should not close an already closed session on error' () {
        given:
        def socket = new PairingWebSocket()
        def session = Mock(WebSocketSession)

        when:
        socket.onError('tower', 'token', 'http://foo.com', session, new IOException('Connection timed out'))

        then:
        _ * session.getId() >> 'session-1'
        1 * session.isOpen() >> false
        0 * session.close(_)
    }
}
