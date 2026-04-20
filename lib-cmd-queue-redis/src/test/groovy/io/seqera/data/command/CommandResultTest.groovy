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
import spock.lang.Unroll

/**
 * Pure unit tests for the {@link CommandResult} factories.
 */
class CommandResultTest extends Specification {

    def 'active() yields a non-terminal RUNNING result with no target stream'() {
        when:
        def r = CommandResult.active()

        then:
        r.status() == CommandStatus.RUNNING
        r.result() == null
        r.error() == null
        r.targetStream() == null
        !r.status().isTerminal()
    }

    def 'running() is a backwards-compatible alias for active()'() {
        when:
        def r = CommandResult.running()

        then:
        r.status() == CommandStatus.RUNNING
        r.targetStream() == null
    }

    def 'activeOnStream() carries the destination stream id'() {
        when:
        def r = CommandResult.activeOnStream('cmd-monitor/v1')

        then:
        r.status() == CommandStatus.RUNNING
        r.targetStream() == 'cmd-monitor/v1'
        !r.status().isTerminal()
    }

    @Unroll
    def 'activeOnStream rejects blank destination: "#dst"'() {
        when:
        CommandResult.activeOnStream(dst)

        then:
        thrown(IllegalArgumentException)

        where:
        dst << [null, '', '   ']
    }

    def 'success/failure/cancelled factories set status and leave targetStream null'() {
        expect:
        CommandResult.success('ok').status() == CommandStatus.SUCCEEDED
        CommandResult.success('ok').targetStream() == null
        CommandResult.failure('nope').status() == CommandStatus.FAILED
        CommandResult.failure('nope').targetStream() == null
        CommandResult.cancelled().status() == CommandStatus.CANCELLED
        CommandResult.cancelled().targetStream() == null
    }

    def 'legacy 3-arg constructor preserves backwards compatibility (null targetStream)'() {
        when:
        def r = new CommandResult(CommandStatus.RUNNING, null, null)

        then:
        r.status() == CommandStatus.RUNNING
        r.targetStream() == null
    }
}
