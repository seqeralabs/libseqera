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

package io.seqera.wave.util

import io.seqera.wave.api.PackagesSpec
import spock.lang.Specification
/**
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
class CondaHelperTest extends Specification {
    def 'should get conda lock file' () {
        expect:
        CondaHelper.condaLock(['https://foo.com/lock.yml']) == 'https://foo.com/lock.yml'

        and:
        CondaHelper.condaLock(null) == null

        and:
        CondaHelper.condaLock([]) == null

        when:
        CondaHelper.condaLock(['foo', 'http://foo.com'])
        then:
        thrown(IllegalArgumentException)
    }

    def 'should get conda file' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def PACKAGES = ['bwa=0.7.15', 'salmon=1.1.1']
        def packages = new PackagesSpec(type: PackagesSpec.Type.CONDA, packages:  PACKAGES, channels: CHANNELS)

        expect:
        CondaHelper.createCondaFileFromPackages(packages) == 'Y2hhbm5lbHM6Ci0gY29uZGEtZm9yZ2UKLSBkZWZhdWx0cwpkZXBlbmRlbmNpZXM6Ci0gYndhPTAuNy4xNQotIHNhbG1vbj0xLjEuMQo='
        and:
        CondaHelper.createCondaFileFromPackages(null) == null
    }

    def 'shoul process conda channels' () {
        given:
        def CHANNELS = ['conda-forge ', ' defaults']
        expect:
        CondaHelper.processCondaChannels(CHANNELS) == ['conda-forge', 'defaults']
    }
}
