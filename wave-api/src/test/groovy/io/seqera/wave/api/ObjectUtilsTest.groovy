/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.api

import spock.lang.Specification
import spock.lang.Unroll

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ObjectUtilsTest extends Specification {

    @Unroll
    def 'should validate isEmpty string' () {
        expect:
        ObjectUtils.isEmpty((String)VALUE) == EXPECTED
        where:
        VALUE       | EXPECTED 
        null        | true
        ''          | true
        'foo'       | false
    }

    @Unroll
    def 'should validate isEmpty Integer' () {
        expect:
        ObjectUtils.isEmpty((Integer)VALUE) == EXPECTED
        where:
        VALUE       | EXPECTED
        null        | true
        0i          | true
        1i          | false
    }

    @Unroll
    def 'should validate isEmpty Long' () {
        expect:
        ObjectUtils.isEmpty((Long)VALUE) == EXPECTED
        where:
        VALUE       | EXPECTED
        null        | true
        0l          | true
        1l          | false
    }

    @Unroll
    def 'should validate isEmpty List' () {
        expect:
        ObjectUtils.isEmpty((List)VALUE) == EXPECTED
        where:
        VALUE       | EXPECTED
        null        | true
        []          | true
        [1]         | false
    }

    @Unroll
    def 'should validate isEmpty Map' () {
        expect:
        ObjectUtils.isEmpty((Map)VALUE) == EXPECTED
        where:
        VALUE       | EXPECTED
        null        | true
        [:]         | true
        [foo:1]     | false
    }
}
