/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.wave.api

import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class BuildCompressionTest extends Specification {

    def 'should set compression mode' () {
        expect:
        new BuildCompression().getMode() == null
        and:
        new BuildCompression().withMode(BuildCompression.Mode.gzip).getMode() == BuildCompression.Mode.gzip
        new BuildCompression().withMode(BuildCompression.Mode.zstd).getMode() == BuildCompression.Mode.zstd
        new BuildCompression().withMode(BuildCompression.Mode.estargz).getMode() == BuildCompression.Mode.estargz
    }

    def 'should set compression level'() {
        expect:
        new BuildCompression().getLevel()==null
        and:
        new BuildCompression().withLevel(1).getLevel() == 1
        new BuildCompression().withLevel(10).getLevel() == 10
    }

    def 'should set compression force'() {
        expect:
        new BuildCompression().getForce() == null
        and:
        new BuildCompression().withForce(false).getForce() == false
        new BuildCompression().withForce(true).getForce() == true
    }

}
