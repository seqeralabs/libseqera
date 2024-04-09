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

package io.seqera.wave.util


import java.nio.file.Files

import io.seqera.wave.config.CondaOpts
import io.seqera.wave.config.SpackOpts
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class DockerHelperTest extends Specification {

    def 'should trim a string' () {
        expect:
        DockerHelper.trim0(STR) == EXPECTED

        where:
        STR         | EXPECTED
        null        | null
        "foo"       | "foo"
        " foo  "    | "foo"
        "'foo"      | "'foo"
        '"foo'      | '"foo'
        and:
        "'foo'"     | "foo"
        "''foo''"   | "foo"
        " 'foo' "   | "foo"
        " ' foo ' " | " foo "
        and:
        '"foo"'     | 'foo'
        '""foo""'   | 'foo'
        ' "foo" '   | 'foo'
    }

    def 'should convert conda packages to list' () {
        expect:
        DockerHelper.condaPackagesToList(STR) == EXPECTED

        where:
        STR                 | EXPECTED
        "foo"               | ["foo"]
        "foo bar"           | ["foo", "bar"]
        "foo 'bar'"         | ["foo", "bar"]
        "foo    'bar'  "    | ["foo", "bar"]
    }

    def 'should convert pip packages to list' () {
        expect:
        DockerHelper.pipPackagesToList(STR) == EXPECTED

        where:
        STR                 | EXPECTED
        "foo"               | ["foo"]
        "foo bar"           | ["foo", "bar"]
        "foo 'bar'"         | ["foo", "bar"]
        "foo    'bar'  "    | ["foo", "bar"]
    }

    def 'should create conda yaml file' () {
        expect:
        DockerHelper.condaPackagesToCondaYaml("foo=1.0 'bar>=2.0'", null)
            ==  '''\
                dependencies:
                - foo=1.0
                - bar>=2.0
                '''.stripIndent(true)


        DockerHelper.condaPackagesToCondaYaml('foo=1.0 bar=2.0', ['channel_a','channel_b'] )
                ==  '''\
                channels:
                - channel_a
                - channel_b
                dependencies:
                - foo=1.0
                - bar=2.0
                '''.stripIndent(true)

        DockerHelper.condaPackagesToCondaYaml('foo=1.0 bar=2.0 pip:numpy pip:pandas', ['channel_a','channel_b'] )
                ==  '''\
                channels:
                - channel_a
                - channel_b
                dependencies:
                - foo=1.0
                - bar=2.0
                - pip
                - pip:
                  - numpy
                  - pandas
                '''.stripIndent(true)
    }

    def 'should map pip packages to conda yaml' () {
        expect:
        DockerHelper.condaPackagesToCondaYaml('pip:numpy pip:panda pip:matplotlib', ['defaults']) ==
                '''\
                channels:
                - defaults
                dependencies:
                - pip
                - pip:
                  - numpy
                  - panda
                  - matplotlib
                '''.stripIndent()

        and:
        DockerHelper.condaPackagesToCondaYaml(null, ['foo']) == null
        DockerHelper.condaPackagesToCondaYaml('  ', ['foo']) == null
    }

    def 'should add conda packages to conda yaml /1' () {
        given:
        def text = '''\
         dependencies:
         - foo=1.0
         - bar=2.0
        '''.stripIndent(true)

        when:
        def result = DockerHelper.condaEnvironmentToCondaYaml(text, null)
        then:
        result == '''\
         dependencies:
         - foo=1.0
         - bar=2.0
        '''.stripIndent(true)

        when:
        result = DockerHelper.condaEnvironmentToCondaYaml(text, ['ch1', 'ch2'])
        then:
        result == '''\
             dependencies:
             - foo=1.0
             - bar=2.0
             channels:
             - ch1
             - ch2
            '''.stripIndent(true)
    }

    def 'should add conda packages to conda yaml /2' () {
        given:
        def text = '''\
         dependencies:
         - foo=1.0
         - bar=2.0
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        def result = DockerHelper.condaEnvironmentToCondaYaml(text, null)
        then:
        result == '''\
         dependencies:
         - foo=1.0
         - bar=2.0
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        result = DockerHelper.condaEnvironmentToCondaYaml(text, ['ch1', 'ch2'])
        then:
        result == '''\
             dependencies:
             - foo=1.0
             - bar=2.0
             channels:
             - hola
             - ciao
             - ch1
             - ch2
            '''.stripIndent(true)
    }

    def 'should add conda packages to conda yaml /3' () {
        given:
        def text = '''\
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        def result = DockerHelper.condaEnvironmentToCondaYaml(text, null)
        then:
        result == '''\
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        result = DockerHelper.condaEnvironmentToCondaYaml(text, ['ch1', 'ch2'])
        then:
        result == '''\
             channels:
             - hola
             - ciao
             - ch1
             - ch2
            '''.stripIndent(true)

        when:
        result = DockerHelper.condaEnvironmentToCondaYaml(text, ['bioconda'])
        then:
        result == '''\
             channels:
             - hola
             - ciao
             - bioconda
            '''.stripIndent(true)
    }


    def 'should add conda packages to conda file /1' () {
        given:
        def condaFile = Files.createTempFile('conda','yaml')
        condaFile.text = '''\
         dependencies:
         - foo=1.0
         - bar=2.0
        '''.stripIndent(true)

        when:
        def result = DockerHelper.condaFileFromPath(condaFile.toString(), null)
        then:
        result.text == '''\
         dependencies:
         - foo=1.0
         - bar=2.0
        '''.stripIndent(true)

        when:
        result = DockerHelper.condaFileFromPath(condaFile.toString(), ['ch1', 'ch2'])
        then:
        result.text == '''\
             dependencies:
             - foo=1.0
             - bar=2.0
             channels:
             - ch1
             - ch2
            '''.stripIndent(true)

        cleanup:
        if( condaFile ) Files.delete(condaFile)
    }

    def 'should add conda packages to conda file /2' () {
        given:
        def condaFile = Files.createTempFile('conda', 'yaml')
        condaFile.text = '''\
         dependencies:
         - foo=1.0
         - bar=2.0
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        def result = DockerHelper.condaFileFromPath(condaFile.toString(), null)
        then:
        result.text == '''\
         dependencies:
         - foo=1.0
         - bar=2.0
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        result = DockerHelper.condaFileFromPath(condaFile.toString(), ['ch1', 'ch2'])
        then:
        result.text == '''\
             dependencies:
             - foo=1.0
             - bar=2.0
             channels:
             - hola
             - ciao
             - ch1
             - ch2
            '''.stripIndent(true)

        cleanup:
        if (condaFile) Files.delete(condaFile)
    }

    def 'should add conda packages to conda file /3' () {
        given:
        def condaFile = Files.createTempFile('conda', 'yaml')
        condaFile.text = '''\
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        def result = DockerHelper.condaFileFromPath(condaFile.toString(), null)
        then:
        result.text == '''\
         channels:
         - hola
         - ciao
        '''.stripIndent(true)

        when:
        result = DockerHelper.condaFileFromPath(condaFile.toString(), ['ch1', 'ch2'])
        then:
        result.text == '''\
             channels:
             - hola
             - ciao
             - ch1
             - ch2
            '''.stripIndent(true)

        when:
        result = DockerHelper.condaFileFromPath(condaFile.toString(), ['bioconda'])
        then:
        result.text == '''\
             channels:
             - hola
             - ciao
             - bioconda
            '''.stripIndent(true)

        cleanup:
        if (condaFile) Files.delete(condaFile)
    }
    
    def 'should create dockerfile content from conda file' () {
        given:
        def CONDA_OPTS = new CondaOpts([basePackages: 'foo::bar'])

        expect:
        DockerHelper.condaFileToDockerFile(CONDA_OPTS)== '''\
                FROM mambaorg/micromamba:1.5.8-lunar
                COPY --chown=$MAMBA_USER:$MAMBA_USER conda.yml /tmp/conda.yml
                RUN micromamba install -y -n base -f /tmp/conda.yml \\
                    && micromamba install -y -n base foo::bar \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create dockerfile content from conda file and base packages' () {

        expect:
        DockerHelper.condaFileToDockerFile(new CondaOpts([:]))== '''\
                FROM mambaorg/micromamba:1.5.8-lunar
                COPY --chown=$MAMBA_USER:$MAMBA_USER conda.yml /tmp/conda.yml
                RUN micromamba install -y -n base -f /tmp/conda.yml \\
                    && micromamba install -y -n base conda-forge::procps-ng \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }


    def 'should create dockerfile content from conda package' () {
        given:
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'
        def CHANNELS = ['conda-forge', 'defaults']
        expect:
        DockerHelper.condaPackagesToDockerFile(PACKAGES, CHANNELS, new CondaOpts([:])) == '''\
                FROM mambaorg/micromamba:1.5.8-lunar
                RUN \\
                    micromamba install -y -n base -c conda-forge -c defaults bwa=0.7.15 salmon=1.1.1 \\
                    && micromamba install -y -n base conda-forge::procps-ng \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create dockerfile with base packages' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def CONDA_OPTS = new CondaOpts([basePackages: 'foo::one bar::two'])
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'

        expect:
        DockerHelper.condaPackagesToDockerFile(PACKAGES, CHANNELS, CONDA_OPTS) == '''\
                FROM mambaorg/micromamba:1.5.8-lunar
                RUN \\
                    micromamba install -y -n base -c conda-forge -c defaults bwa=0.7.15 salmon=1.1.1 \\
                    && micromamba install -y -n base foo::one bar::two \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create dockerfile content with custom channels' () {
        given:
        def CHANNELS = 'foo,bar'.tokenize(',')
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'

        expect:
        DockerHelper.condaPackagesToDockerFile(PACKAGES, CHANNELS, new CondaOpts([:])) == '''\
                FROM mambaorg/micromamba:1.5.8-lunar
                RUN \\
                    micromamba install -y -n base -c foo -c bar bwa=0.7.15 salmon=1.1.1 \\
                    && micromamba install -y -n base conda-forge::procps-ng \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create dockerfile content with custom conda config' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def CONDA_OPTS = [mambaImage:'my-base:123', commands: ['USER my-user', 'RUN apt-get update -y && apt-get install -y nano']]
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'

        expect:
        DockerHelper.condaPackagesToDockerFile(PACKAGES, CHANNELS, new CondaOpts(CONDA_OPTS)) == '''\
                FROM my-base:123
                RUN \\
                    micromamba install -y -n base -c conda-forge -c defaults bwa=0.7.15 salmon=1.1.1 \\
                    && micromamba install -y -n base conda-forge::procps-ng \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                USER my-user
                RUN apt-get update -y && apt-get install -y nano
                '''.stripIndent()
    }


    def 'should create dockerfile content with remote conda lock' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def OPTS = [mambaImage:'my-base:123', commands: ['USER my-user', 'RUN apt-get update -y && apt-get install -y procps']]
        def PACKAGES = 'https://foo.com/some/conda-lock.yml'

        expect:
        DockerHelper.condaPackagesToDockerFile(PACKAGES, CHANNELS, new CondaOpts(OPTS)) == '''\
                FROM my-base:123
                RUN \\
                    micromamba install -y -n base -c conda-forge -c defaults -f https://foo.com/some/conda-lock.yml \\
                    && micromamba install -y -n base conda-forge::procps-ng \\
                    && micromamba clean -a -y
                USER root
                ENV PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                USER my-user
                RUN apt-get update -y && apt-get install -y procps
                '''.stripIndent()
    }


    def 'should create dockerfile content from spack package' () {
        given:
        def PACKAGES = 'bwa@0.7.15 salmon@1.1.1'

        expect:
        DockerHelper.spackPackagesToSpackFile(PACKAGES, Mock(SpackOpts)).text == '''\
                spack:
                  specs: [bwa@0.7.15, salmon@1.1.1]
                  concretizer: {unify: true, reuse: false}
                '''.stripIndent(true)

        DockerHelper.spackFileToDockerFile(new SpackOpts())== '''\
                # Runner image
                FROM {{spack_runner_image}}
                
                COPY --from=builder /opt/spack-env /opt/spack-env
                COPY --from=builder /opt/software /opt/software
                COPY --from=builder /opt/._view /opt/._view
                
                # Entrypoint for Singularity
                RUN mkdir -p /.singularity.d/env && \\
                    cp -p /opt/spack-env/z10_spack_environment.sh /.singularity.d/env/91-environment.sh
                # Entrypoint for Docker
                RUN echo "#!/usr/bin/env bash\\n\\nset -ef -o pipefail\\nsource /opt/spack-env/z10_spack_environment.sh\\nexec \\"\\\$@\\"" \\
                    >/opt/spack-env/spack_docker_entrypoint.sh && chmod a+x /opt/spack-env/spack_docker_entrypoint.sh
                
                
                ENTRYPOINT [ "/opt/spack-env/spack_docker_entrypoint.sh" ]
                CMD [ "/bin/bash" ]
                '''.stripIndent()
    }

    def 'should create dockerfile content with custom spack config' () {
        given:
        def SPACK_OPTS = [ commands:['USER hola'] ]
        def PACKAGES = 'bwa@0.7.15 salmon@1.1.1'

        expect:
        DockerHelper.spackPackagesToSpackFile(PACKAGES, Mock(SpackOpts)).text == '''\
                spack:
                  specs: [bwa@0.7.15, salmon@1.1.1]
                  concretizer: {unify: true, reuse: false}
                '''.stripIndent(true)

        DockerHelper.spackFileToDockerFile(new SpackOpts(SPACK_OPTS))== '''\
                # Runner image
                FROM {{spack_runner_image}}
                
                COPY --from=builder /opt/spack-env /opt/spack-env
                COPY --from=builder /opt/software /opt/software
                COPY --from=builder /opt/._view /opt/._view
                
                # Entrypoint for Singularity
                RUN mkdir -p /.singularity.d/env && \\
                    cp -p /opt/spack-env/z10_spack_environment.sh /.singularity.d/env/91-environment.sh
                # Entrypoint for Docker
                RUN echo "#!/usr/bin/env bash\\n\\nset -ef -o pipefail\\nsource /opt/spack-env/z10_spack_environment.sh\\nexec \\"\\\$@\\"" \\
                    >/opt/spack-env/spack_docker_entrypoint.sh && chmod a+x /opt/spack-env/spack_docker_entrypoint.sh
                
                USER hola
                
                ENTRYPOINT [ "/opt/spack-env/spack_docker_entrypoint.sh" ]
                CMD [ "/bin/bash" ]
                '''.stripIndent()
    }


    def 'should create dockerfile content from spack file' () {
        expect:
        DockerHelper.spackFileToDockerFile(new SpackOpts())== '''\
                # Runner image
                FROM {{spack_runner_image}}
                
                COPY --from=builder /opt/spack-env /opt/spack-env
                COPY --from=builder /opt/software /opt/software
                COPY --from=builder /opt/._view /opt/._view
                
                # Entrypoint for Singularity
                RUN mkdir -p /.singularity.d/env && \\
                    cp -p /opt/spack-env/z10_spack_environment.sh /.singularity.d/env/91-environment.sh
                # Entrypoint for Docker
                RUN echo "#!/usr/bin/env bash\\n\\nset -ef -o pipefail\\nsource /opt/spack-env/z10_spack_environment.sh\\nexec \\"\\\$@\\"" \\
                    >/opt/spack-env/spack_docker_entrypoint.sh && chmod a+x /opt/spack-env/spack_docker_entrypoint.sh
                
                
                ENTRYPOINT [ "/opt/spack-env/spack_docker_entrypoint.sh" ]
                CMD [ "/bin/bash" ]
                '''.stripIndent()
    }

    def 'should return empty packages' () {
        when:
        def result = DockerHelper.spackPackagesToSpackYaml(null, new SpackOpts())
        then:
        result == null
    }

    def 'should convert a list of packages to a spack yaml' () {
        when:
        def result = DockerHelper.spackPackagesToSpackYaml('foo@1.2.3 x=one bar @2', new SpackOpts())
        then:
        result == '''\
            spack:
              specs: [foo@1.2.3 x=one, bar @2]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
    }


    def 'should add base packages' () {
        when:
        def result = DockerHelper.spackPackagesToSpackYaml(null, new SpackOpts(basePackages: 'foo bar'))
        then:
        result == '''\
            spack:
              specs: [foo, bar]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)

        when:
        result = DockerHelper.spackPackagesToSpackYaml('this that @2', new SpackOpts(basePackages: 'foo bar @1'))
        then:
        result == '''\
            spack:
              specs: [foo, bar @1, this, that @2]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
    }

    def 'should convert a list of packages to a spack file' () {
        when:
        def result = DockerHelper.spackPackagesToSpackFile('foo@1.2.3 x=one bar @2', new SpackOpts())
        then:
        result.text == '''\
            spack:
              specs: [foo@1.2.3 x=one, bar @2]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
    }

    def 'should parse a spack packages string' () {
        expect:
        DockerHelper.spackPackagesToList(PACKAGES) == EXPECTED

        where:
        PACKAGES            | EXPECTED
         null               | null
        'alpha'             | ['alpha']
        "'alpha@1.0'"       | ['alpha@1.0']
        "alpha 'delta@1.0'" | ['alpha', 'delta@1.0']
        'alpha delta'       | ['alpha', 'delta']
        'alpha delta gamma' | ['alpha', 'delta', 'gamma']
        'alpha 1aa'         | ['alpha', '1aa']
        'alpha    2bb  '    | ['alpha', '2bb']
        and:
        'alpha x=1'         | ['alpha x=1']
        'alpha x=1 delta'   | ['alpha x=1', 'delta']
        'alpha ^foo delta'  | ['alpha ^foo', 'delta']
        and:
        '^alpha ~beta foo'  | ['^alpha ~beta', 'foo']   // <-- this should not be valid

    }

    def 'should merge spack file and base package' () {
        given:
        def folder = Files.createTempDirectory('test')
        and:
        def SPACK_FILE1 = folder.resolve('spack1.yaml')
        SPACK_FILE1.text = '''\
            spack:
              specs: [foo@1.2.3 x=one, bar @2]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
        and:
        def SPACK_FILE2 = folder.resolve('spack2.yaml')
        SPACK_FILE2.text = '''\
            spack:
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
        and:
        def SPACK_FILE3 = folder.resolve('spack3.yaml')
        SPACK_FILE3.text = '''\
            foo:
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)

        when:
        def result = DockerHelper.addPackagesToSpackFile(null, new SpackOpts())
        then:
        result == null

        when:
        result = DockerHelper.addPackagesToSpackFile(SPACK_FILE1.toString(), new SpackOpts())
        then:
        result.toString() == SPACK_FILE1.toString()

        when:
        result = DockerHelper.addPackagesToSpackFile(SPACK_FILE1.toString(), new SpackOpts(basePackages: 'alpha delta'))
        then:
        result.text == '''\
            spack:
              specs: [foo@1.2.3 x=one, bar @2, alpha, delta]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)


        when:
        result = DockerHelper.addPackagesToSpackFile(SPACK_FILE2.toString(), new SpackOpts(basePackages: 'alpha delta'))
        then:
        result.text == '''\
            spack:
              concretizer: {unify: true, reuse: false}
              specs: [alpha, delta]
            '''.stripIndent(true)

        when:
        DockerHelper.addPackagesToSpackFile(SPACK_FILE3.toString(), new SpackOpts(basePackages: 'foo'))
        then:
        thrown(IllegalArgumentException)

        when:
        DockerHelper.addPackagesToSpackFile('missing file', new SpackOpts(basePackages: 'foo'))
        then:
        thrown(IllegalArgumentException)

        when:
        DockerHelper.addPackagesToSpackFile('missing file', new SpackOpts())
        then:
        thrown(IllegalArgumentException)

        cleanup:
        folder?.deleteDir()
    }


    def 'should merge spack yaml and base package' () {
        given:
        def SPACK_FILE1 = '''\
            spack:
              specs: [foo@1.2.3 x=one, bar @2]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
        and:
        def SPACK_FILE2 = '''\
            spack:
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)
        and:
        def SPACK_FILE3 = '''\
            foo:
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)

        when:
        def result = DockerHelper.addPackagesToSpackYaml(null, new SpackOpts())
        then:
        result == null

        when:
        result = DockerHelper.addPackagesToSpackYaml(SPACK_FILE1, new SpackOpts())
        then:
        result == SPACK_FILE1

        when:
        result = DockerHelper.addPackagesToSpackYaml(SPACK_FILE1, new SpackOpts(basePackages: 'alpha delta'))
        then:
        result == '''\
            spack:
              specs: [foo@1.2.3 x=one, bar @2, alpha, delta]
              concretizer: {unify: true, reuse: false}
            '''.stripIndent(true)


        when:
        result = DockerHelper.addPackagesToSpackYaml(SPACK_FILE2, new SpackOpts(basePackages: 'alpha delta'))
        then:
        result == '''\
            spack:
              concretizer: {unify: true, reuse: false}
              specs: [alpha, delta]
            '''.stripIndent(true)

        when:
        DockerHelper.addPackagesToSpackYaml(SPACK_FILE3, new SpackOpts(basePackages: 'foo'))
        then:
        thrown(IllegalArgumentException)

        when:
        DockerHelper.addPackagesToSpackYaml('missing file', new SpackOpts(basePackages: 'foo'))
        then:
        thrown(IllegalArgumentException)
    }

    /* *********************************************************************************
     * conda packages to singularity tests
     * *********************************************************************************/

    def 'should create singularity content from conda file' () {
        given:
        def CONDA_OPTS = new CondaOpts([basePackages: 'foo::bar=1.0'])

        expect:
        DockerHelper.condaFileToSingularityFile(CONDA_OPTS)== '''\
                BootStrap: docker
                From: mambaorg/micromamba:1.5.8-lunar
                %files
                    {{wave_context_dir}}/conda.yml /scratch/conda.yml
                %post
                    micromamba install -y -n base -f /scratch/conda.yml
                    micromamba install -y -n base foo::bar=1.0
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create singularity content from conda file and base packages' () {

        expect:
        DockerHelper.condaFileToSingularityFile(new CondaOpts([:]))== '''\
                BootStrap: docker
                From: mambaorg/micromamba:1.5.8-lunar
                %files
                    {{wave_context_dir}}/conda.yml /scratch/conda.yml
                %post
                    micromamba install -y -n base -f /scratch/conda.yml
                    micromamba install -y -n base conda-forge::procps-ng
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }


    def 'should create singularity content from conda package' () {
        given:
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'
        def CHANNELS = ['conda-forge', 'defaults']
        expect:
        DockerHelper.condaPackagesToSingularityFile(PACKAGES, CHANNELS, new CondaOpts([:])) == '''\
                BootStrap: docker
                From: mambaorg/micromamba:1.5.8-lunar
                %post
                    micromamba install -y -n base -c conda-forge -c defaults bwa=0.7.15 salmon=1.1.1
                    micromamba install -y -n base conda-forge::procps-ng
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create singularity with base packages' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def CONDA_OPTS = new CondaOpts([basePackages: 'foo::one bar::two'])
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'

        expect:
        DockerHelper.condaPackagesToSingularityFile(PACKAGES, CHANNELS, CONDA_OPTS) == '''\
                BootStrap: docker
                From: mambaorg/micromamba:1.5.8-lunar
                %post
                    micromamba install -y -n base -c conda-forge -c defaults bwa=0.7.15 salmon=1.1.1
                    micromamba install -y -n base foo::one bar::two
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create singularity content with custom channels' () {
        given:
        def CHANNELS = 'foo,bar'.tokenize(',')
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'

        expect:
        DockerHelper.condaPackagesToSingularityFile(PACKAGES, CHANNELS, new CondaOpts([:])) == '''\
                BootStrap: docker
                From: mambaorg/micromamba:1.5.8-lunar
                %post
                    micromamba install -y -n base -c foo -c bar bwa=0.7.15 salmon=1.1.1
                    micromamba install -y -n base conda-forge::procps-ng
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                '''.stripIndent()
    }

    def 'should create singularity content with custom conda config' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def CONDA_OPTS = [mambaImage:'my-base:123', commands: ['install --this --that', 'apt-get update -y && apt-get install -y nano']]
        def PACKAGES = 'bwa=0.7.15 salmon=1.1.1'

        expect:
        DockerHelper.condaPackagesToSingularityFile(PACKAGES, CHANNELS, new CondaOpts(CONDA_OPTS)) == '''\
                BootStrap: docker
                From: my-base:123
                %post
                    micromamba install -y -n base -c conda-forge -c defaults bwa=0.7.15 salmon=1.1.1
                    micromamba install -y -n base conda-forge::procps-ng
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                %post
                    install --this --that
                    apt-get update -y && apt-get install -y nano
                '''.stripIndent()
    }


    def 'should create singularity content with remote conda lock' () {
        given:
        def CHANNELS = ['conda-forge', 'defaults']
        def OPTS = [mambaImage:'my-base:123', commands: ['apt-get update -y && apt-get install -y procps']]
        def PACKAGES = 'https://foo.com/some/conda-lock.yml'

        expect:
        DockerHelper.condaPackagesToSingularityFile(PACKAGES, CHANNELS, new CondaOpts(OPTS)) == '''\
                BootStrap: docker
                From: my-base:123
                %post
                    micromamba install -y -n base -c conda-forge -c defaults -f https://foo.com/some/conda-lock.yml
                    micromamba install -y -n base conda-forge::procps-ng
                    micromamba clean -a -y
                %environment
                    export PATH="$MAMBA_ROOT_PREFIX/bin:$PATH"
                %post
                    apt-get update -y && apt-get install -y procps
                '''.stripIndent()
    }

    def 'should create singularity content from spack file'(){
        given:
        def SPACK_OPTS = [ commands:['USER hola']]
        expect:
        DockerHelper.spackFileToSingularityFile(new SpackOpts(SPACK_OPTS)) =='''\
            Bootstrap: docker
            From: {{spack_runner_image}}
            stage: final

            %files from build
                /opt/spack-env /opt/spack-env
                /opt/software /opt/software
                /opt/._view /opt/._view
                /opt/spack-env/z10_spack_environment.sh /.singularity.d/env/91-environment.sh
            
            %post
                USER hola
            '''.stripIndent()
    }

}
