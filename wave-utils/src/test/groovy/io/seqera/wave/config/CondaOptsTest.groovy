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
import spock.lang.Unroll

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CondaOptsTest extends Specification {

    def 'check conda options' () {
        when:
        def opts = new CondaOpts([:])
        then:
        opts.mambaImage == CondaOpts.DEFAULT_MAMBA_IMAGE
        opts.basePackages == 'conda-forge::procps-ng'
        !opts.commands

        when:
        opts = new CondaOpts([
                mambaImage:'foo:latest',
                commands: ['this','that'],
                basePackages: 'some::more-package'
        ])
        then:
        opts.mambaImage == 'foo:latest'
        opts.basePackages == 'some::more-package'
        opts.commands == ['this','that']


        when:
        opts = new CondaOpts([
                basePackages: null
        ])
        then:
        !opts.basePackages
        !opts.commands
    }

    @Unroll
    def "should convert to string" () {
        expect:
        new CondaOpts(OPTS).toString() == EXPECTED
        where:
        OPTS    | EXPECTED
        [:]     | "CondaOpts(mambaImage=mambaorg/micromamba:1.5.1; basePackages=conda-forge::procps-ng, commands=null)"
        [mambaImage: 'foo:1.0', basePackages: 'this that', commands: ['X','Y']] \
                | "CondaOpts(mambaImage=foo:1.0; basePackages=this that, commands=X,Y)"
    }
}
