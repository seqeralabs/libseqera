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

import java.util.Arrays;
import java.util.Base64;

/**
 * Model an encrypted piece of data
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class Sealed {

    private static final int SALT_LEN = 16;

    private final byte[] data;

    private final byte[] salt;

    public Sealed(byte[] encrypted, byte[] salt) {
        if (salt.length != SALT_LEN) {
            throw new IllegalArgumentException("Salt must be exactly " + SALT_LEN + " bytes");
        }
        this.salt = salt;
        this.data = encrypted;
    }

    public byte[] getSalt() {
        return salt;
    }

    public String getSaltString() {
        return Base64.getEncoder().encodeToString(salt);
    }

    public byte[] getData() {
        return data;
    }

    public String getDataString() {
        return Base64.getEncoder().encodeToString(data);
    }

    public byte[] serialize() {
        byte[] result = new byte[SALT_LEN + data.length];
        System.arraycopy(salt, 0, result, 0, SALT_LEN);
        System.arraycopy(data, 0, result, SALT_LEN, data.length);
        return result;
    }

    @Override
    public String toString() {
        return Base64.getEncoder().encodeToString(serialize());
    }

    public static Sealed deserialize(byte[] source) {
        byte[] salt = new byte[SALT_LEN];
        byte[] data = new byte[source.length - SALT_LEN];
        System.arraycopy(source, 0, salt, 0, SALT_LEN);
        System.arraycopy(source, SALT_LEN, data, 0, data.length);
        return new Sealed(data, salt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Sealed sealed = (Sealed) o;
        return Arrays.equals(data, sealed.data) && Arrays.equals(salt, sealed.salt);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(data);
        result = 31 * result + Arrays.hashCode(salt);
        return result;
    }
}
