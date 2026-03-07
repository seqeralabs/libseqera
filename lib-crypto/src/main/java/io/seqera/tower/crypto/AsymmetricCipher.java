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

import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * This class is a singleton that implements an asymmetric cipher (using Public/Private key cryptography)
 * able to encrypt/decrypt large payloads.
 *
 * @author Andrea Tortorella <andrea.tortorella@seqera.io>
 */
public class AsymmetricCipher {

    private static final AsymmetricCipher INSTANCE = new AsymmetricCipher();

    private static final KeyFactory keyFactory;

    static {
        try {
            keyFactory = KeyFactory.getInstance("RSA");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private AsymmetricCipher() {}

    /**
     * Returns an instance of AsymmetricCipher
     */
    public static AsymmetricCipher getInstance() {
        return INSTANCE;
    }

    /**
     * Generates a public/private key pair suitable for encryption of {@link EncryptedPacket}
     */
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Decodes an encoded public key generated with {@link #generateKeyPair()}
     */
    public PublicKey decodePublicKey(byte[] key) {
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(key);
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Decodes an encoded private key generated with AsymmetricCipher.generateKeyPair
     */
    public PrivateKey decodePrivateKey(byte[] key) {
        try {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(key);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Decrypts a public encrypted packet
     */
    public byte[] decrypt(EncryptedPacket packet, PrivateKey key) {
        if (!packet.getVersion().equals(EncryptedPacket.VERSION_1))
            throw new IllegalArgumentException("unsupported packet version");
        if (!packet.isUsePublic())
            throw new IllegalArgumentException("packet uses private encryption");
        return decrypt0(key, packet.getEncryptedKey(), packet.getEncryptedData());
    }

    /**
     * Decrypts a private encrypted packet
     */
    public byte[] decrypt(EncryptedPacket packet, PublicKey key) {
        if (!packet.getVersion().equals(EncryptedPacket.VERSION_1))
            throw new IllegalArgumentException("unsupported packet version");
        if (packet.isUsePublic())
            throw new IllegalArgumentException("packet uses public encryption");
        return decrypt0(key, packet.getEncryptedKey(), packet.getEncryptedData());
    }

    /**
     * Encrypts data with private key
     */
    public EncryptedPacket encrypt(PrivateKey key, byte[] data) {
        return encrypt0(key, false, data);
    }

    /**
     * Encrypts data with public key
     */
    public EncryptedPacket encrypt(PublicKey key, byte[] data) {
        return encrypt0(key, true, data);
    }

    private static byte[] decrypt0(Key rsaKey, byte[] encryptedSessionKey, byte[] encryptedData) {
        SecretKey sessionKey = decryptKey(rsaKey, encryptedSessionKey);
        return decryptData(sessionKey, encryptedData);
    }

    private static EncryptedPacket encrypt0(Key key, boolean isPublicKey, byte[] data) {
        try {
            SecretKey secretKey = generateSymmetricKey();
            byte[] encryptedData = encryptData(secretKey, data);
            byte[] encryptedSecretKey = encryptKey(key, secretKey);
            EncryptedPacket packet = new EncryptedPacket();
            packet.setUsePublic(isPublicKey);
            packet.setEncryptedKey(encryptedSecretKey);
            packet.setEncryptedData(encryptedData);
            return packet;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SecretKey decryptKey(Key key, byte[] sessionKey) {
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] encodedKey = cipher.doFinal(sessionKey);
            return new SecretKeySpec(encodedKey, "AES");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] encryptKey(Key key, SecretKey sessionKey) {
        try {
            byte[] encodedKey = sessionKey.getEncoded();
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(encodedKey);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] decryptData(Key key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] encryptData(SecretKey key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static SecretKey generateSymmetricKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
