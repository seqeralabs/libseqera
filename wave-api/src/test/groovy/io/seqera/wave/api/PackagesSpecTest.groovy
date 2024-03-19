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

package io.seqera.wave.api

import spock.lang.Specification
/**
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
class PackagesSpecTest extends Specification {
    def 'should check equals and hashcode' () {
        given:
        def packages1 = new PackagesSpec(type: PackagesSpec.Type.CONDA, envFile: 'foo', packages: ['bar'], channels: ['1', '2'])
        def packages2 = new PackagesSpec(type: PackagesSpec.Type.CONDA, envFile: 'foo', packages: ['bar'], channels: ['1', '2'])
        def packages3 = new PackagesSpec(type: PackagesSpec.Type.SPACK, envFile: 'foo', packages: ['bar'])

        expect:
        packages1 == packages2
        packages1 != packages3

        and:
        packages1.hashCode() == packages2.hashCode()
        packages1.hashCode() != packages3.hashCode()
    }

    def 'should infer the correct type' () {
        given:
        def packages1 = new PackagesSpec(type: PackagesSpec.Type.CONDA, envFile: 'foo', packages: ['bar'], channels: ['1', '2'])
        def packages2 = new PackagesSpec(type: PackagesSpec.Type.SPACK, envFile: 'foo', packages: ['bar'])

        expect:
        packages1.type == PackagesSpec.Type.CONDA
        packages2.type == PackagesSpec.Type.SPACK
    }
}
