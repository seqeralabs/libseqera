package io.seqera.wave.api

import spock.lang.Specification

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
