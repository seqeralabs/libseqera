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

package io.seqera.tower.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Models a bundle of secret key and payload produced by AsymmetricCipher
 *
 * @author Andrea Tortorella <andrea.tortorella@seqera.io>
 */
public class EncryptedPacket {

    // this is safe to use as a separator since it doesn't appear in base64
    protected static final String SEPARATOR = "\\";

    private static final String PUBLIC_MODE = "P";
    private static final String PRIVATE_MODE = "p";

    // used to mark the version so the format can be updated
    public static final String VERSION_1 = "1";

    private final String version = VERSION_1;
    private boolean usePublic;
    private byte[] encryptedKey;
    private byte[] encryptedData;

    public EncryptedPacket() {}

    public String getVersion() {
        return version;
    }

    public boolean isUsePublic() {
        return usePublic;
    }

    public boolean getUsePublic() {
        return usePublic;
    }

    public void setUsePublic(boolean usePublic) {
        this.usePublic = usePublic;
    }

    public byte[] getEncryptedKey() {
        return encryptedKey;
    }

    public void setEncryptedKey(byte[] encryptedKey) {
        this.encryptedKey = encryptedKey;
    }

    public byte[] getEncryptedData() {
        return encryptedData;
    }

    public void setEncryptedData(byte[] encryptedData) {
        this.encryptedData = encryptedData;
    }

    /**
     * Encodes the packet as a string
     *
     * V1 format is 1\mode\key\payload
     * @return the encoded packet
     */
    public String encode() {
        String encodedKey = Base64.getEncoder().encodeToString(encryptedKey);
        String encodedData = Base64.getEncoder().encodeToString(encryptedData);
        String mode = usePublic ? PUBLIC_MODE : PRIVATE_MODE;

        return VERSION_1 + SEPARATOR + mode + SEPARATOR + encodedKey + SEPARATOR + encodedData;
    }

    /**
     * Attempts to decode a packet from a string
     *
     * @param packet string to be decoded
     * @return a decoded EncryptedPacket
     * @throws IllegalArgumentException if the packet encoding is not valid
     */
    public static EncryptedPacket decode(String packet) {
        // Split on backslash and filter empty tokens (matching Groovy's tokenize behavior)
        String[] parts = packet.split("\\\\", -1);
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }

        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Data is not an encrypted packet");
        }
        if (!tokens.get(0).equals(VERSION_1)) {
            throw new IllegalArgumentException("Version " + tokens.get(0) + " of encrypted packets is not supported");
        }
        if (tokens.size() != 4) {
            throw new IllegalArgumentException("Invalid encrypted packet: illegal number of tokens (must be 4)");
        }
        if (!tokens.get(1).equals(PUBLIC_MODE) && !tokens.get(1).equals(PRIVATE_MODE)) {
            throw new IllegalArgumentException("Invalid encrypted packet: unrecognized mode should be [P] or [p]");
        }

        boolean usePublic = tokens.get(1).equals(PUBLIC_MODE);
        byte[] key = Base64.getDecoder().decode(tokens.get(2));
        byte[] data = Base64.getDecoder().decode(tokens.get(3));

        EncryptedPacket result = new EncryptedPacket();
        result.setUsePublic(usePublic);
        result.setEncryptedKey(key);
        result.setEncryptedData(data);
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EncryptedPacket that = (EncryptedPacket) o;
        return usePublic == that.usePublic
                && Arrays.equals(encryptedKey, that.encryptedKey)
                && Arrays.equals(encryptedData, that.encryptedData)
                && (version != null ? version.equals(that.version) : that.version == null);
    }

    @Override
    public int hashCode() {
        int result = version != null ? version.hashCode() : 0;
        result = 31 * result + (usePublic ? 1 : 0);
        result = 31 * result + Arrays.hashCode(encryptedKey);
        result = 31 * result + Arrays.hashCode(encryptedData);
        return result;
    }
}
