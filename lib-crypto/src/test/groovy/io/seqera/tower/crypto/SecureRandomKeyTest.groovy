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
class SecureRandomKeyTest extends Specification {

    def 'should encode decode key' () {
        when:
        def key = SecureRandomKey.generate()
        then:
        key.length()==22

        when:
        def bytes = SecureRandomKey.fromString(key)
        then:
        SecureRandomKey.encoder.encodeToString(bytes) == key
    }
}
