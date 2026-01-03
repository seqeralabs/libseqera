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
import spock.lang.Unroll

/**
 * Tests for {@link ContainerState} parsing and state detection.
 *
 * @author Paolo Di Tommaso
 */
class ContainerStateTest extends Specification {

    @Unroll
    def 'should parse status=#STATUS with exitCode=#EXIT_CODE'() {
        when:
        def state = ContainerState.parse(INPUT)

        then:
        state.status() == STATUS
        state.exitCode() == EXIT_CODE

        where:
        INPUT           | STATUS      | EXIT_CODE
        'running,0'     | 'running'   | 0
        'exited,0'      | 'exited'    | 0
        'exited,1'      | 'exited'    | 1
        'exited,137'    | 'exited'    | 137
        'created,0'     | 'created'   | 0
        'paused,0'      | 'paused'    | 0
        'dead,255'      | 'dead'      | 255
        'restarting,0'  | 'restarting'| 0
        'running,'      | 'running'   | null
        'running'       | 'running'   | null
    }

    def 'should detect running state'() {
        when:
        def state = ContainerState.parse('running,0')

        then:
        state.isRunning()
        !state.isExited()
        !state.isPending()
        !state.isSucceeded()
    }

    def 'should detect exited success state'() {
        when:
        def state = ContainerState.parse('exited,0')

        then:
        !state.isRunning()
        state.isExited()
        state.isSucceeded()
        !state.isPending()
    }

    def 'should detect exited failure state'() {
        when:
        def state = ContainerState.parse('exited,1')

        then:
        !state.isRunning()
        state.isExited()
        !state.isSucceeded()
    }

    def 'should detect created as pending'() {
        when:
        def state = ContainerState.parse('created,0')

        then:
        state.isPending()
        !state.isRunning()
        !state.isExited()
    }

    def 'should detect paused as pending'() {
        when:
        def state = ContainerState.parse('paused,0')

        then:
        state.isPending()
        !state.isRunning()
        !state.isExited()
    }

    def 'should handle null exit code for succeeded check'() {
        when:
        def state = new ContainerState('exited', null)

        then:
        state.isExited()
        !state.isSucceeded()
    }

    def 'should handle signal exit codes'() {
        when:
        def state = ContainerState.parse('exited,137')  // SIGKILL

        then:
        state.isExited()
        !state.isSucceeded()
        state.exitCode() == 137
    }
}
