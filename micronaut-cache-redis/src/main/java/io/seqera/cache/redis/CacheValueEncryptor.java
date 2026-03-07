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
package io.seqera.cache.redis;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import io.seqera.tower.crypto.CryptoHelper;
import io.seqera.tower.crypto.Sealed;

/**
 * AES-256-CBC encryptor for Redis cache values.
 * Derives a 256-bit key from a password and cache name using PBKDF2WithHmacSHA256
 * via {@link CryptoHelper#deriveKey(String, byte[])}.
 * Each encryption prepends a random 16-byte IV to the ciphertext.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CacheValueEncryptor {

    private static final int SALT_LENGTH = 16;

    private final SecretKeySpec secretKey;

    public CacheValueEncryptor(String password, String cacheName) {
        byte[] salt = deriveSalt(cacheName);
        this.secretKey = CryptoHelper.deriveKey(password, salt);
    }

    public byte[] encrypt(byte[] data) {
        return CryptoHelper.encrypt(secretKey, data).serialize();
    }

    public byte[] decrypt(byte[] data) {
        Sealed sealed = Sealed.deserialize(data);
        return CryptoHelper.decrypt(secretKey, sealed);
    }

    private static byte[] deriveSalt(String cacheName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cacheName.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, SALT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
