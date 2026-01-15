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
package io.seqera.data.command

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.seqera.data.command.store.CommandStateStore
import jakarta.inject.Inject
import org.junit.jupiter.api.TestInstance
import spock.lang.Specification

import java.time.Duration
import java.time.Instant

/**
 * End-to-end tests for the CommandService.
 */
@MicronautTest(packages = ["io.seqera.data.stream"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandServiceTest extends Specification implements TestPropertyProvider {

    @Inject
    CommandService commandService

    @Inject
    CommandStateStore store

    @Override
    Map<String, String> getProperties() {
        return [
            'command-queue.poll-interval': '100ms',
            'command-queue.checker.interval': '500ms'
        ]
    }

    def setup() {
        // Clear store and register test handler
        store.clear()
        commandService.registerHandler(new TestCommandHandler())
    }

    def 'should submit command and transition through statuses'() {
        given:
        def params = new TestParams(42, 'fast')
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'test', params)

        when: 'command is submitted'
        def commandId = commandService.submit(command)

        then: 'command ID is returned'
        commandId == command.id()

        when: 'wait for processing'
        sleep(3000)  // Allow time for queue polling
        def state = commandService.getState(commandId).orElseThrow()

        then: 'status transitions to SUCCEEDED'
        state.status() == CommandStatus.SUCCEEDED
        state.result() != null
        state.completedAt() != null

        and: 'result can be retrieved'
        def result = commandService.getResult(commandId, TestResult).orElseThrow()
        result.message == 'Processed'
        result.processedValue == 42
    }

    def 'should handle command failure'() {
        given:
        def params = new TestParams(0, 'fail')
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'test', params)

        when: 'command is submitted'
        commandService.submit(command)

        and: 'wait for processing'
        sleep(3000)
        def state = commandService.getState(command.id()).orElseThrow()

        then: 'status is FAILED with error message'
        state.status() == CommandStatus.FAILED
        state.error() == 'Intentional failure'
        state.completedAt() != null
    }

    def 'should cancel pending command'() {
        given:
        def params = new TestParams(42, 'fast')
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'test', params)

        when: 'command is submitted and immediately cancelled'
        commandService.submit(command)
        def cancelled = commandService.cancel(command.id())

        then: 'cancel returns true'
        cancelled

        and: 'state shows cancelled'
        def state = commandService.getState(command.id()).orElseThrow()
        state.status() == CommandStatus.CANCELLED
    }

    def 'should not cancel already terminal command'() {
        given:
        def params = new TestParams(42, 'fast')
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'test', params)

        when: 'command is submitted and completed'
        commandService.submit(command)
        sleep(3000)

        and: 'try to cancel'
        def cancelled = commandService.cancel(command.id())

        then: 'cancel returns false'
        !cancelled

        and: 'state is still SUCCEEDED'
        def state = commandService.getState(command.id()).orElseThrow()
        state.status() == CommandStatus.SUCCEEDED
    }

    def 'should check status periodically for long-running commands'() {
        given:
        def params = new TestParams(99, 'slow')
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'test', params)

        when: 'command is submitted'
        commandService.submit(command)

        and: 'wait briefly'
        sleep(500)
        def state = commandService.getState(command.id()).orElseThrow()

        then: 'status is RUNNING'
        state.status() == CommandStatus.RUNNING

        when: 'wait for periodic checker'
        sleep(3000)
        state = commandService.getState(command.id()).orElseThrow()

        then: 'status eventually becomes SUCCEEDED'
        state.status() == CommandStatus.SUCCEEDED
        def result = commandService.getResult(command.id(), TestResult).orElseThrow()
        result.message == 'Slow done'
        result.processedValue == 99
    }

    def 'should handle unknown command type'() {
        given:
        def params = new TestParams(42, 'fast')
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'unknown-type', params)

        when: 'command is submitted'
        commandService.submit(command)

        and: 'wait for processing'
        sleep(3000)
        def state = commandService.getState(command.id()).orElseThrow()

        then: 'status is FAILED with error about missing handler'
        state.status() == CommandStatus.FAILED
        state.error().contains('No handler')
    }
}

// Test fixtures

class TestParams {
    int value
    String mode

    TestParams() {} // Default constructor for Jackson

    TestParams(int value, String mode) {
        this.value = value
        this.mode = mode
    }
}

class TestResult {
    String message
    int processedValue

    TestResult() {} // Default constructor for Jackson

    TestResult(String message, int processedValue) {
        this.message = message
        this.processedValue = processedValue
    }
}

class TestCommand implements Command<TestParams> {
    private String _id
    private String _type = 'test'
    private TestParams _params

    TestCommand(String id, String type, TestParams params) {
        this._id = id
        this._type = type
        this._params = params
    }

    @Override
    String id() { return _id }

    @Override
    String type() { return _type }

    @Override
    TestParams params() { return _params }
}

class TestCommandHandler implements CommandHandler<TestParams, TestResult> {
    private Instant startTime

    @Override
    String type() { 'test' }

    @Override
    CommandResult<TestResult> execute(Command<TestParams> command) {
        def params = command.params()

        if (params.mode == 'fail') {
            return CommandResult.failure('Intentional failure')
        }

        if (params.mode == 'slow') {
            startTime = Instant.now()
            return CommandResult.running()
        }

        def result = new TestResult('Processed', params.value)
        return CommandResult.success(result)
    }

    @Override
    CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
        def params = command.params()

        if (params.mode == 'slow') {
            // Simulate completion after 2 seconds
            if (Duration.between(state.startedAt(), Instant.now()).toMillis() > 2000) {
                return CommandResult.success(new TestResult('Slow done', params.value))
            }
        }

        return CommandResult.running()
    }
}
