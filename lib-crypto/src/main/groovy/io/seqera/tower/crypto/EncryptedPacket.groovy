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

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * Models a bundle of secret key and payload produced by AsymmetricCipher
 *
 *
 * @author Andrea Tortorella <andrea.tortorella@seqera.io>
 */
@CompileStatic
@EqualsAndHashCode
class EncryptedPacket {

    // this is safe to use as a separator since it doesn't appear in base64
    protected static final String SEPARATOR = "\\"

    private static final String PUBLIC_MODE = "P"
    private static final String PRIVATE_MODE = "p"

    // used to mark the version so the format can be updated
    public static final String VERSION_1 = "1";

    final String version = VERSION_1
    boolean usePublic
    byte[] encryptedKey
    byte[] encryptedData

    /**
     * Encodes the packet as a string
     *
     * V1 format is 1\mode\key\payload
     * @return the encoded packet
     */
    String encode() {
        final encodedKey = Base64.encoder.encodeToString(encryptedKey)
        final encodedData = Base64.encoder.encodeToString(encryptedData)
        final mode = usePublic? PUBLIC_MODE : PRIVATE_MODE

        return [VERSION_1, mode, encodedKey, encodedData].join(SEPARATOR)
    }

    /**
     * Attempts to decode a packet from a string
     *
     * @param packet string to be decoded
     * @return a decoded EncryptedPacket
     * @throws IllegalArgumentException if the packet encoding is not valid
     *
     */
    static EncryptedPacket decode(String packet) {
        def tokens = packet.tokenize(SEPARATOR)
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Data is not an encrypted packet")
        }
        if (tokens[0] != VERSION_1) {
            throw new IllegalArgumentException("Version ${tokens[0]} of encrypted packets is not supported")
        }
        if (tokens.size() != 4) {
            throw new IllegalArgumentException("Invalid encrypted packet: illegal number of tokens (must be 4)")
        }
        if (tokens[1] != PUBLIC_MODE && tokens[1] != PRIVATE_MODE) {
            throw new IllegalArgumentException("Invalid encrypted packet: unrecognized mode should be [P] or [p]")
        }
        final usePublic = tokens[1] == PUBLIC_MODE
        final key = tokens[2].decodeBase64()
        final data = tokens[3].decodeBase64()

        return new EncryptedPacket(
                usePublic: usePublic,
                encryptedKey: key,
                encryptedData: data
        )
    }
}
