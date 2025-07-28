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

package io.seqera.tower.crypto

import spock.lang.Specification

class EncryptedPacketTest extends Specification{


    def 'should encode and decode valid packets'() {
        given: 'a packet'
        def packet = new EncryptedPacket(
                usePublic: true,
                encryptedKey: new byte[100],
                encryptedData: new byte[100],
        )


        when: 'encoding it'
        def encoded = packet.encode()

        then: 'decoding it should give back the same content'
        def decoded = EncryptedPacket.decode(encoded)
        decoded == packet
    }


    def 'should fail to decode when encoded packet is empty'() {
        given: 'a fake empty packet'
        def fakePacket = ""

        when: 'decoding the packet'
        EncryptedPacket.decode(fakePacket)

        then: 'it should fail with an exception'
        def e = thrown(IllegalArgumentException)
        e.message == "Data is not an encrypted packet"
    }


    def 'should check version mismatch'() {
        given: 'an encoded packet with wrong version'
        def packet = new EncryptedPacket(
                usePublic: true,
                encryptedKey: new byte[100],
                encryptedData: new byte[100]
        ).encode()
        /// we replace the version manually in the encoding
        def tokens = packet.tokenize(EncryptedPacket.SEPARATOR)
        tokens[0] = "2"
        def alteredVersionPacket = tokens.join(EncryptedPacket.SEPARATOR)

        when: 'decoding the packet'
        EncryptedPacket.decode(alteredVersionPacket)

        then: 'an illegal argument exception is thrown'
        def e = thrown(IllegalArgumentException)
        e.message =~ /Version.*is not supported/
    }


    def 'should fail to decode v1 packet that do not respect the format'() {
        given: 'some invalid packet'
        def packet = [EncryptedPacket.VERSION_1, mode, key, data].findAll {!it.isEmpty()}.join(EncryptedPacket.SEPARATOR)

        when: 'decoding the packet'
        EncryptedPacket.decode(packet)

        then: 'it should fail'
        thrown(IllegalArgumentException)

        where:
        mode | key | data
        "Q"  | "a" | "a"
        "p"  | "a" | ""
        "P"  | ""  | "a"
        ""   | "a" | "a"
    }
}
