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

package io.seqera.wave.config

import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class SpackOptsTest extends Specification {

    def 'check spack default options' () {
        given:
        def opts = new SpackOpts()
        expect:
        opts.commands == null
        opts.basePackages == null
    }

    def 'check spack custom opts' () {
        given:
        def opts = new SpackOpts([
                basePackages: 'foo bar',
                commands: ['run','--this','--that']
        ])

        expect:
        opts.commands == ['run','--this','--that']
        and:
        opts.basePackages == 'foo bar'
    }
}
