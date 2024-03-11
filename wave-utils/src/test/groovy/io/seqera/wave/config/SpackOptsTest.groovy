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

package io.seqera.wave.config

import spock.lang.Specification
import spock.lang.Unroll

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

    @Unroll
    def "should convert to string" () {
        expect:
        new SpackOpts(OPTS).toString() == EXPECTED
        where:
        OPTS    | EXPECTED
        [:]     | "SpackOpts(basePackages=null, commands=null)"
        [basePackages: 'this that', commands: ['X','Y']] \
                | "SpackOpts(basePackages=this that, commands=X,Y)"
    }
}
