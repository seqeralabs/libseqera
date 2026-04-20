/*
 * Copyright 2026, Seqera Labs
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
package io.seqera.data.command

import spock.lang.Specification

/**
 * Unit tests for the hand-off mechanism used when a command should continue
 * its lifecycle on a different queue.
 */
class CommandResultHandedOffTest extends Specification {

    def 'handedOff yields a non-terminal HANDED_OFF result'() {
        when:
        def r = CommandResult.handedOff()

        then:
        r.status() == CommandStatus.HANDED_OFF
        r.result() == null
        r.error() == null
        !r.status().isTerminal()
    }

    def 'HANDED_OFF status is not terminal'() {
        expect:
        !CommandStatus.HANDED_OFF.isTerminal()
    }

    def 'only SUCCEEDED, FAILED and CANCELLED are terminal'() {
        expect:
        !CommandStatus.PENDING.isTerminal()
        !CommandStatus.SUBMITTED.isTerminal()
        !CommandStatus.RUNNING.isTerminal()
        !CommandStatus.HANDED_OFF.isTerminal()
        CommandStatus.SUCCEEDED.isTerminal()
        CommandStatus.FAILED.isTerminal()
        CommandStatus.CANCELLED.isTerminal()
    }

    def 'HANDED_OFF result transitions a SUBMITTED state to RUNNING before ACK'() {
        given: 'a state currently in SUBMITTED'
        def state = CommandState.submitted('cmd-1', 'test', null)

        when: 'applying HANDED_OFF semantics (started())'
        def after = state.started()

        then: 'persisted state becomes RUNNING so the destination queue dispatches to checkStatus()'
        after.status() == CommandStatus.RUNNING
    }
}
