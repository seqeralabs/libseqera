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
class SealedTest extends Specification {

    def 'should create secure' () {
        given:
        def salt = [0,1,2,3,4,5,6,7,8,9,0,1,2,3,4,5] as byte[]
        def data = [10,20,30,40] as byte[]

        when:
        def secure = new Sealed(data, salt)

        then:
        secure.data == data
        secure.salt == salt
        and:
        secure.dataString == 'ChQeKA=='
        secure.saltString == 'AAECAwQFBgcICQABAgMEBQ=='
        and:
        secure.serialize() == [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5, 10, 20, 30, 40] as byte[]
        secure.toString() == 'AAECAwQFBgcICQABAgMEBQoUHig='

        // salt must be 16 bytes
        when:
        new Sealed(data, [1, 2, 3] as byte[])
        then:
        thrown(AssertionError)
    }

}
