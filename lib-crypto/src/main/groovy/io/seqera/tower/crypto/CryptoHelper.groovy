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

import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

import groovy.transform.CompileStatic
/**
 * Implement secret hashing and symmetric encryption routines
 *
 * https://www.baeldung.com/java-password-hashing
 *
 * NOTE: Make sure to have Java Crypto extension https://stackoverflow.com/a/6481658
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class CryptoHelper {

    private final static SecureRandom random = new SecureRandom()

    private static final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")

    private final String secretKey

    CryptoHelper(String secretKey) {
        this.secretKey = secretKey
    }

    Sealed encrypt(String strToEncrypt, byte[] salt) {
        encrypt(strToEncrypt.getBytes("UTF-8"), salt)
    }

    Sealed encrypt(byte[] bytesToEncrypt, byte[] salt) {
        final spec = new PBEKeySpec(secretKey.toCharArray(), salt, 65536, 256);
        final tmp = factory.generateSecret(spec);
        final keySpec = new SecretKeySpec(tmp.getEncoded(), "AES");

        final iv = rndSalt()
        final cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
        final encrypted = cipher.doFinal(bytesToEncrypt)
        new Sealed(encrypted, iv)
    }


    byte[] decrypt(Sealed secure, byte[] salt) {
        final spec = new PBEKeySpec(secretKey.toCharArray(), salt, 65536, 256);
        final tmp = factory.generateSecret(spec);
        final keySpec = new SecretKeySpec(tmp.getEncoded(), "AES");

        final cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(secure.salt));
        cipher.doFinal(secure.data)
    }

    /**
     * Salt generator i.e. random piece of data
     * @return
     */
    static byte[] rndSalt() {
        byte[] salt = new byte[16]
        random.nextBytes(salt)
        return salt
    }

    /**
     * Implements PBKDF2 secret hashing
     *
     * https://www.baeldung.com/java-password-hashing#pbkdf2-bcrypt-and-scrypt
     *
     * @param password Data string to be hashed
     * @param salt A *salt* data e.g. random bytes (16). If not provided is generated automatically.
     * @return The sealed (i.e encrypted) string
     */
    static Sealed encodeSecret(String password, byte[] salt=null) {
        final salt0 = salt ?: rndSalt()
        final spec = new PBEKeySpec(password.toCharArray(), salt0, 65536, 128)
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        final secret = factory.generateSecret(spec).getEncoded()
        return new Sealed(secret, salt0)
    }

    static boolean checkSecret(String password, Sealed secret) {
        final verify = encodeSecret(password, secret.salt)
        verify == secret
    }

}
