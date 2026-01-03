/*
 * Copyright 2025, Seqera Labs
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
 */
package io.seqera.docker

import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Shared

/**
 * Integration tests for {@link DockerCli}.
 * Requires Docker daemon to be running.
 *
 * @author Paolo Di Tommaso
 */
@Timeout(60)
class DockerCliTest extends Specification {

    @Shared
    DockerCli docker = new DockerCli()

    def setupSpec() {
        if (!DockerCli.isAvailable()) {
            throw new IllegalStateException("Docker is not available. Please install Docker and ensure the daemon is running to execute these tests.")
        }
    }

    def 'should run container and get logs'() {
        given:
        def name = "test-docker-cli-${System.currentTimeMillis()}"
        def config = new ContainerConfig()
                .name(name)
                .image('alpine:latest')
                .command(['echo', 'HELLO_DOCKER_CLI'])

        when:
        docker.run(config)
        def state = docker.inspect(name)
        def logs = docker.logs(name)

        then:
        state.isExited()
        state.exitCode() == 0
        logs.contains('HELLO_DOCKER_CLI')

        cleanup:
        docker.rm(name, true)
    }

    def 'should detect failed container'() {
        given:
        def name = "test-docker-cli-fail-${System.currentTimeMillis()}"
        def config = new ContainerConfig()
                .name(name)
                .image('alpine:latest')
                .command(['sh', '-c', 'exit 42'])

        when:
        docker.run(config)
        def state = docker.inspect(name)

        then:
        state.isExited()
        state.exitCode() == 42
        !state.isSucceeded()

        cleanup:
        docker.rm(name, true)
    }

    def 'should stop running container'() {
        given:
        def name = "test-docker-cli-stop-${System.currentTimeMillis()}"
        def config = new ContainerConfig()
                .name(name)
                .image('alpine:latest')
                .command(['sleep', '30'])
                .detach(true)  // run in background so we can stop it

        when:
        docker.run(config)
        def runningState = docker.inspect(name)

        then:
        runningState.isRunning()

        when:
        docker.stop(name, 1)
        def stoppedState = docker.inspect(name)

        then:
        stoppedState.isExited()

        cleanup:
        docker.rm(name, true)
    }

    def 'should run with environment variables'() {
        given:
        def name = "test-docker-cli-env-${System.currentTimeMillis()}"
        def config = new ContainerConfig()
                .name(name)
                .image('alpine:latest')
                .env([MY_VAR: 'my_value'])
                .command(['sh', '-c', 'echo $MY_VAR'])

        when:
        docker.run(config)
        def logs = docker.logs(name)

        then:
        logs.contains('my_value')

        cleanup:
        docker.rm(name, true)
    }

    def 'should run in detached mode and poll for completion'() {
        given:
        def name = "test-docker-cli-detach-${System.currentTimeMillis()}"
        def config = new ContainerConfig()
                .name(name)
                .image('alpine:latest')
                .command(['sh', '-c', 'echo DETACHED_OUTPUT && exit 0'])
                .detach(true)

        when: 'run in detached mode'
        def containerId = docker.run(config)

        then: 'returns container id immediately'
        containerId

        when: 'poll until exited'
        ContainerState state
        for (int i = 0; i < 10; i++) {
            state = docker.inspect(name)
            if (state.isExited()) break
            sleep(100)
        }

        then: 'container completed successfully'
        state.isExited()
        state.exitCode() == 0
        state.isSucceeded()
        docker.logs(name).contains('DETACHED_OUTPUT')

        cleanup:
        docker.rm(name, true)
    }

    def 'should handle non-zero exit in detached mode'() {
        given:
        def name = "test-docker-cli-detach-fail-${System.currentTimeMillis()}"
        def config = new ContainerConfig()
                .name(name)
                .image('alpine:latest')
                .command(['sh', '-c', 'echo DETACHED_FAIL && exit 77'])
                .detach(true)

        when: 'run in detached mode'
        docker.run(config)

        and: 'poll until exited'
        ContainerState state
        for (int i = 0; i < 10; i++) {
            state = docker.inspect(name)
            if (state.isExited()) break
            sleep(100)
        }

        then: 'container failed with expected exit code'
        state.isExited()
        state.exitCode() == 77
        !state.isSucceeded()
        docker.logs(name).contains('DETACHED_FAIL')

        cleanup:
        docker.rm(name, true)
    }
}
