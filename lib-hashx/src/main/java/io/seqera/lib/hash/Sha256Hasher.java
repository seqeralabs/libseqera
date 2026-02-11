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
 */
package io.seqera.lib.hash;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 based implementation of {@link Hasher}.
 * Accumulates data and produces a 64-bit hash from the first 8 bytes of the SHA-256 digest.
 *
 * @author Paolo Di Tommaso
 */
public class Sha256Hasher implements Hasher {

    private final MessageDigest digest;

    public Sha256Hasher() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public Hasher putString(String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        return this;
    }

    @Override
    public Hasher putBoolean(boolean value) {
        digest.update((byte) (value ? 1 : 0));
        return this;
    }

    @Override
    public Hasher putInt(int value) {
        digest.update(ByteBuffer.allocate(4).putInt(value).array());
        return this;
    }

    @Override
    public Hasher putSeparator() {
        digest.update((byte) 0);
        return this;
    }

    @Override
    public long toLong() {
        final byte[] hash = digest.digest();
        return ByteBuffer.wrap(hash, 0, 8).getLong();
    }
}
