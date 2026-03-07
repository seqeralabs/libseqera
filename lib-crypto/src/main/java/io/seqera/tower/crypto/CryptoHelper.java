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

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Implement secret hashing and symmetric encryption routines
 *
 * https://www.baeldung.com/java-password-hashing
 *
 * NOTE: Make sure to have Java Crypto extension https://stackoverflow.com/a/6481658
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CryptoHelper {

    private static final SecureRandom random = new SecureRandom();

    private static final SecretKeyFactory factory;

    static {
        try {
            factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final String secretKey;

    public CryptoHelper(String secretKey) {
        this.secretKey = secretKey;
    }

    public Sealed encrypt(String strToEncrypt, byte[] salt) {
        return encrypt(strToEncrypt.getBytes(StandardCharsets.UTF_8), salt);
    }

    public Sealed encrypt(byte[] bytesToEncrypt, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secretKey.toCharArray(), salt, 65536, 256);
            var tmp = factory.generateSecret(spec);
            var keySpec = new SecretKeySpec(tmp.getEncoded(), "AES");

            byte[] iv = rndSalt();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(bytesToEncrypt);
            return new Sealed(encrypted, iv);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] decrypt(Sealed secure, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secretKey.toCharArray(), salt, 65536, 256);
            var tmp = factory.generateSecret(spec);
            var keySpec = new SecretKeySpec(tmp.getEncoded(), "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(secure.getSalt()));
            return cipher.doFinal(secure.getData());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Derive an AES-256 SecretKeySpec from a password and salt using PBKDF2WithHmacSHA256.
     *
     * @param password the password
     * @param salt the salt (16 bytes recommended)
     * @return the derived AES key
     */
    public static SecretKeySpec deriveKey(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
        try {
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Salt generator i.e. random piece of data
     */
    public static byte[] rndSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return salt;
    }

    /**
     * Implements PBKDF2 secret hashing
     *
     * https://www.baeldung.com/java-password-hashing#pbkdf2-bcrypt-and-scrypt
     *
     * @param password Data string to be hashed
     * @return The sealed (i.e encrypted) string
     */
    public static Sealed encodeSecret(String password) {
        return encodeSecret(password, null);
    }

    /**
     * Implements PBKDF2 secret hashing
     *
     * @param password Data string to be hashed
     * @param salt A salt data e.g. random bytes (16). If not provided is generated automatically.
     * @return The sealed (i.e encrypted) string
     */
    public static Sealed encodeSecret(String password, byte[] salt) {
        try {
            byte[] salt0 = salt != null ? salt : rndSalt();
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt0, 65536, 128);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] secret = skf.generateSecret(spec).getEncoded();
            return new Sealed(secret, salt0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean checkSecret(String password, Sealed secret) {
        Sealed verify = encodeSecret(password, secret.getSalt());
        return verify.equals(secret);
    }
}
