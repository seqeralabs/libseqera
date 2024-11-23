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

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode

/**
 * Model an encrypted piece of data
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
@EqualsAndHashCode
class Sealed {
    private static int SALT_LEN = 16

    final private byte[] data

    final private byte[] salt

    Sealed(byte[] encrypted, byte[] salt) {
        assert salt.length == SALT_LEN
        this.salt = salt
        this.data = encrypted
    }

    byte[] getSalt() { salt }

    String getSaltString() { Base64.getEncoder().encodeToString(salt) }

    byte[] getData() { data }

    String getDataString() { Base64.getEncoder().encodeToString(data) }

    byte[] serialize() {
        byte[] result = new byte[ SALT_LEN + data.length ]
        System.arraycopy(salt, 0, result, 0, SALT_LEN)
        System.arraycopy(data, 0, result, SALT_LEN, data.length)
        return result
    }

    String toString() {
        Base64.getEncoder().encodeToString(serialize())
    }

    static Sealed deserialize(byte[] source) {
        byte[] salt = new byte[SALT_LEN]
        byte[] data = new byte[ source.length-SALT_LEN ]
        System.arraycopy(source, 0, salt, 0, SALT_LEN)
        System.arraycopy(source, SALT_LEN, data, 0, data.length)
        new Sealed(data, salt)
    }
}
