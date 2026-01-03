/*
 * Copyright 2013-2025, Seqera Labs
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
package io.seqera.docker.cli

import io.seqera.docker.cli.ContainerConfig
import spock.lang.Specification

/**
 * Tests for {@link io.seqera.docker.cli.ContainerConfig} builder pattern.
 *
 * @author Paolo Di Tommaso
 */
class ContainerConfigTest extends Specification {

    def 'should build basic config'() {
        when:
        def config = new ContainerConfig()
                .name('test-container')
                .image('alpine:latest')

        then:
        config.getName() == 'test-container'
        config.getImage() == 'alpine:latest'
    }

    def 'should build config with command'() {
        when:
        def config = new ContainerConfig()
                .name('test')
                .image('alpine')
                .command(['echo', 'hello'])

        then:
        config.getCommand() == ['echo', 'hello']
    }

    def 'should build config with environment'() {
        when:
        def config = new ContainerConfig()
                .name('test')
                .image('alpine')
                .env([FOO: 'bar', BAZ: 'qux'])

        then:
        config.getEnvironment() == [FOO: 'bar', BAZ: 'qux']
    }

    def 'should build config with platform'() {
        when:
        def config = new ContainerConfig()
                .name('test')
                .image('alpine')
                .platform('linux/amd64')

        then:
        config.getPlatform() == 'linux/amd64'
    }

    def 'should enable FUSE support'() {
        when:
        def config = new ContainerConfig()
                .name('test')
                .image('alpine')
                .withFuseSupport()

        then:
        config.isPrivileged()
        config.getCapAdd().contains('SYS_ADMIN')
        config.getDevices().contains('/dev/fuse:/dev/fuse:rwm')
    }

    def 'should build complete config'() {
        when:
        def config = new ContainerConfig()
                .name('fusion-job')
                .image('my-image:latest')
                .command(['bash', '-c', 'echo hello'])
                .env([AWS_ACCESS_KEY_ID: 'xxx', AWS_SECRET_ACCESS_KEY: 'yyy'])
                .platform('linux/arm64')
                .withFuseSupport()

        then:
        config.getName() == 'fusion-job'
        config.getImage() == 'my-image:latest'
        config.getCommand() == ['bash', '-c', 'echo hello']
        config.getEnvironment().size() == 2
        config.getPlatform() == 'linux/arm64'
        config.isPrivileged()
    }

    def 'should allow chaining'() {
        expect:
        new ContainerConfig()
                .name('a')
                .image('b')
                .command(['c'])
                .env([d: 'e'])
                .platform('f')
                .withFuseSupport() instanceof ContainerConfig
    }
}
