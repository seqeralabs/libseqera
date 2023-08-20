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
class CondaOptsTest extends Specification {

    def 'check conda options' () {
        when:
        def opts = new CondaOpts([:])
        then:
        opts.mambaImage == CondaOpts.DEFAULT_MAMBA_IMAGE
        !opts.basePackages
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
    }
}
