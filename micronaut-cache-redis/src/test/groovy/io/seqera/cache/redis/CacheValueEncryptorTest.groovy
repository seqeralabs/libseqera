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
package io.seqera.cache.redis

import spock.lang.Specification

class CacheValueEncryptorTest extends Specification {

    def 'should encrypt and decrypt data'() {
        given:
        def password = 'my-secret-password'
        def salt = Base64.encoder.encodeToString(new byte[16])
        def encryptor = new CacheValueEncryptor(password, salt)
        def original = 'Hello, World!'.bytes

        when:
        def encrypted = encryptor.encrypt(original)
        def decrypted = encryptor.decrypt(encrypted)

        then:
        decrypted == original
        encrypted != original
    }

    def 'should produce different ciphertexts for same input (random IV)'() {
        given:
        def encryptor = new CacheValueEncryptor('password', Base64.encoder.encodeToString(new byte[16]))
        def original = 'same data'.bytes

        when:
        def encrypted1 = encryptor.encrypt(original)
        def encrypted2 = encryptor.encrypt(original)

        then:
        encrypted1 != encrypted2

        and: 'both decrypt to original'
        encryptor.decrypt(encrypted1) == original
        encryptor.decrypt(encrypted2) == original
    }

    def 'should fail to decrypt with wrong password'() {
        given:
        def salt = Base64.encoder.encodeToString(new byte[16])
        def encryptor1 = new CacheValueEncryptor('password1', salt)
        def encryptor2 = new CacheValueEncryptor('password2', salt)
        def original = 'secret data'.bytes

        when:
        def encrypted = encryptor1.encrypt(original)
        encryptor2.decrypt(encrypted)

        then:
        thrown(RuntimeException)
    }

    def 'should fail to decrypt with wrong salt'() {
        given:
        def salt1 = Base64.encoder.encodeToString(new byte[16])
        def salt2Bytes = new byte[16]; salt2Bytes[0] = 1
        def salt2 = Base64.encoder.encodeToString(salt2Bytes)
        def encryptor1 = new CacheValueEncryptor('password', salt1)
        def encryptor2 = new CacheValueEncryptor('password', salt2)
        def original = 'secret data'.bytes

        when:
        def encrypted = encryptor1.encrypt(original)
        encryptor2.decrypt(encrypted)

        then:
        thrown(RuntimeException)
    }

    def 'should handle empty byte array'() {
        given:
        def encryptor = new CacheValueEncryptor('password', Base64.encoder.encodeToString(new byte[16]))
        def original = new byte[0]

        when:
        def encrypted = encryptor.encrypt(original)
        def decrypted = encryptor.decrypt(encrypted)

        then:
        decrypted == original
    }

    def 'should reject invalid salt'() {
        when:
        new CacheValueEncryptor('password', Base64.encoder.encodeToString(new byte[8]))

        then:
        thrown(IllegalArgumentException)
    }
}
