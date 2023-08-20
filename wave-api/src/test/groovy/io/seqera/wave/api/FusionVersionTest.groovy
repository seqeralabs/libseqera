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

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class FusionVersionTest extends Specification {

    def 'should parse version from uri' () {
        expect:
        FusionVersion.from(STR) == EXPECTED

        where:
        EXPECTED                            | STR
        null                                | null
        new FusionVersion('2','amd64')      | 'https://foo.com/v2-amd64.json'
        new FusionVersion('22','amd64')     | 'https://foo.com/v22-amd64.json'
        new FusionVersion('2.1','amd64')    | 'https://foo.com/v2.1-amd64.json'
        new FusionVersion('2.11','amd64')   | 'https://foo.com/v2.11-amd64.json'
        new FusionVersion('2.1.3','amd64')  | 'https://foo.com/v2.1.3-amd64.json'
        new FusionVersion('2.1.3','arm64')  | 'https://foo.com/v2.1.3-arm64.json'
        new FusionVersion('2.1.33','arm64') | 'https://foo.com/v2.1.33-arm64.json'
        new FusionVersion('2.1.3a','arm64') | 'https://foo.com/v2.1.3a-arm64.json'
        new FusionVersion('2.1.3.a','arm64')| 'https://foo.com/v2.1.3.a-arm64.json'

    }

}
