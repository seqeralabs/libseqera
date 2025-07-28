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


import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CryptoHelperTest extends Specification {

    static String SALT = "boooooooooom!!!!"
    static String SECRET = 'ssshhhhhhhhhhh!!!!'

    def 'should encrypt and decrypt a secret' () {
        given:
        def crypto = new CryptoHelper(SECRET)
        def secret = "don't tell this to anybody!"

        when:
        def secure = crypto.encrypt(secret, SALT.bytes)
        and:
        def plainBytes = crypto.decrypt(secure, SALT.bytes)

        then:
        secret == new String(plainBytes)
    }


    def 'should hash password' () {
        given:
        def password = 'Hello world'

        when:
        def secure = CryptoHelper.encodeSecret(password)

        then:
        CryptoHelper.checkSecret(password, secure)

    }

    def 'should ser-des secure' () {
        given:
        def password = 'Hello world'
        def helper = new CryptoHelper(null)
        and:
        def secure = helper.encodeSecret(password)

        when:
        def bytes = secure.serialize()
        and:
        def copy = Sealed.deserialize(bytes)
        then:
        secure == copy

    }


}
