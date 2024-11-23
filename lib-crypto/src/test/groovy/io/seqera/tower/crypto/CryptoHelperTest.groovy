/*
 * Copyright (c) 2019-2020, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
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
