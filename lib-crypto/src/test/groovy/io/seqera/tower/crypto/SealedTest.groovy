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
