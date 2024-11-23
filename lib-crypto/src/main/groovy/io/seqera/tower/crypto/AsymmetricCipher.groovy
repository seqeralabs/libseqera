/*
 * Copyright 2024, Seqera Labs
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

import java.security.Key
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

import groovy.transform.CompileStatic

/**
 * This class is a singleton that implements an asymmetric cipher (using Public/Private key cryptography)
 * able to encrypt/decrypt large payloads.
 *
 * @author Andrea Tortorella <andrea.tortorella@seqera.io>
 */
@CompileStatic
class AsymmetricCipher {

    /*
     * IMPLEMENTATION NOTES:
     * RSA and other asymmetric encryption schemes are limited to encrypt at most
     * keySize-1 / 8 bytes of clear text.
     * This implies you cannot process generic payloads.
     *
     * AsymmetricCipher overcomes this limitation by using a symmetric key for the encryption
     * of the actual payload, and asymmetric encryption on this key.
     * This two encrypted information are then packed together and can be safely sent over the
     * network, since only who owns the other half of the asymmetric key pair can actually read the
     * symmetric key and thus have access to the decrypted payload.
     */

    private static final AsymmetricCipher INSTANCE = new AsymmetricCipher()

    private static final KeyFactory keyFactory = KeyFactory.getInstance('RSA')

    private AsymmetricCipher() {}

    /**
     * Returns an instance of AsymmetricCipher
     *
     * @return AsymmetricCipher
     */
    static AsymmetricCipher getInstance() {
        return INSTANCE
    }


    /**
     * Generates a public/private key pair suitable for encryption of {@link EncryptedPacket}
     *
     * @return
     */
    KeyPair generateKeyPair() {
        final gen = KeyPairGenerator.getInstance('RSA')
        gen.initialize(2048)
        final keyPair = gen.generateKeyPair()
        return keyPair
    }


    /**
     * Decodes an encoded public key generated with {@link #generateKeyPair()}
     *
     * @param key an encoded public key
     * @return the decoded public key
     */
    PublicKey decodePublicKey(byte[] key) {
        final keySpec = new X509EncodedKeySpec(key)
        return keyFactory.generatePublic(keySpec)
    }


    /**
     * Decodes an encoded private key generated with AsymmetricCipher.generateKeyPair
     *
     * @param key an encoded private key
     * @return teh decoded private key
     */
    PrivateKey decodePrivateKey(byte[] key) {
        final keySpec = new PKCS8EncodedKeySpec(key)
        return keyFactory.generatePrivate(keySpec)
    }


    /**
     * Decrypts a public encrypted packet
     *
     * @param packet
     * @param key the corresponding private key
     * @return the clear-text data
     * @throws IllegalArgumentException if the version or the mode are not supported
     */
    byte[] decrypt(EncryptedPacket packet, PrivateKey key) {
        if (packet.version !== EncryptedPacket.VERSION_1) throw new IllegalArgumentException("unsupported packet version")
        if (!packet.usePublic) throw new IllegalArgumentException("packet uses private encryption")
        return decrypt0(key,packet.encryptedKey, packet.encryptedData)
    }


    /**
     * Decrypts a private encrypted packet
     *
     * @param packet
     * @param key the corresponding public key
     * @return the clear-text data
     * @throws IllegalArgumentException if the version of the mode are not suported
     */
    byte[] decrypt(EncryptedPacket packet, PublicKey key) {
        if (packet.version !== EncryptedPacket.VERSION_1) throw new IllegalArgumentException("unsupported packet version")
        if (packet.usePublic) throw new IllegalArgumentException("packet uses public encryption")
        return decrypt0(key,packet.encryptedKey, packet.encryptedData)
    }


    /**
     * Encrypts data with private key
     *
     * @param key a private key generated with the AsymmetricCipher
     * @param data the payload to be encrypted
     * @return EncryptedPacket that bundles session key and encrypted payload
     */
    EncryptedPacket encrypt(PrivateKey key, byte[] data) {
        return encrypt0(key, false, data)
    }


    /**
     * Encrypts data with public key
     *
     * @param key a public key generated with the AsymmetricCipher
     * @param data the payload to be encrypted
     * @return EncryptedPacket that bundles session key and encrypted payload
     */
    EncryptedPacket encrypt(PublicKey key,byte[] data) {
        return encrypt0(key, true, data)
    }


    private static byte[] decrypt0(Key rsaKey, byte[] encryptedSessionKey, byte[] encryptedData) {
        final sessionKey = decryptKey(rsaKey, encryptedSessionKey)
        final data = decryptData(sessionKey, encryptedData)
        return data
    }


    private static EncryptedPacket encrypt0(Key key, boolean isPublicKey, byte[] data) {
        final secretKey = generateSymmetricKey()
        final encryptedData = encryptData(secretKey, data)
        final encryptedSecretKey = encryptKey(key, secretKey)
        final packet = new EncryptedPacket(
                usePublic: isPublicKey,
                encryptedKey: encryptedSecretKey,
                encryptedData:  encryptedData
        )
        return packet
    }


    private static SecretKey decryptKey(Key key, byte[] sessionKey) {
        final cipher = Cipher.getInstance('RSA')
        cipher.init(Cipher.DECRYPT_MODE, key)
        final encodedKey = cipher.doFinal(sessionKey)
        return new SecretKeySpec(encodedKey, 'AES')
    }


    private static byte[] encryptKey(Key key, SecretKey sessionKey) {
        final encodedKey = sessionKey.getEncoded()
        final cipher = Cipher.getInstance('RSA')
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(encodedKey)
    }


    private static byte[] decryptData(Key key, byte[] data) {
        final cipher = Cipher.getInstance('AES')
        cipher.init(Cipher.DECRYPT_MODE, key)
        return cipher.doFinal(data)

    }


    private static byte[] encryptData(SecretKey key, byte[] data) {
        final cipher = Cipher.getInstance('AES')
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }


    private static SecretKey generateSymmetricKey() {
        final keyFactory = KeyGenerator.getInstance("AES")
        keyFactory.init(128);
        return keyFactory.generateKey();
    }
}
