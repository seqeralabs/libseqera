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

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

import io.seqera.data.command.store.CommandStateStore
import io.seqera.lock.Lock
import io.seqera.lock.LockManager
import spock.lang.Specification

/**
 * Unit tests for the async dispatch and worker logic of {@link CommandServiceImpl},
 * driving processCommand/runCommand directly with mocked collaborators.
 */
class CommandServiceProcessingTest extends Specification {

    CommandServiceImpl svc
    CommandStateStore store
    LockManager lockManager
    ExecutorService pool

    def setup() {
        svc = new CommandServiceImpl()
        store = Mock(CommandStateStore)
        lockManager = Mock(LockManager)
        pool = Mock(ExecutorService)
        svc.@store = store
        svc.@lockManager = lockManager
        svc.@pool = pool
        svc.@config = new CommandConfig() {}
    }

    private static CommandState submitted(String id) {
        CommandState.submitted(id, 'test', new TestParams(1, 'fast'))
    }

    private static CommandState running(String id) {
        submitted(id).started()
    }

    // ---- processCommand gating (poll thread) ----

    def 'acknowledges when state is absent'() {
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.empty()
        0 * pool.execute(_)
        ack
    }

    def 'acknowledges when state is already terminal'() {
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1').completed(new TestResult('x', 1)))
        0 * pool.execute(_)
        ack
    }

    def 'marks FAILED and acknowledges when no handler registered'() {
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1'))
        1 * store.save({ it.status() == CommandStatus.FAILED })
        0 * pool.execute(_)
        ack
    }

    def 'dispatches EXECUTE and stays queued for a fresh SUBMITTED command with lock acquired'() {
        given:
        svc.registerHandler(new InProcHandler())
        def lock = Mock(Lock)
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1'))
        1 * lockManager.tryAcquire('cmd-queue/lock/c1') >> lock
        1 * pool.execute(_)
        !ack
        svc.@inFlight.contains('c1')
    }

    def 'stays queued and does not dispatch when the lock is held by another replica'() {
        given:
        svc.registerHandler(new InProcHandler())
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1'))
        1 * lockManager.tryAcquire(_) >> null
        0 * pool.execute(_)
        !ack
        !svc.@inFlight.contains('c1')
    }

    def 'does not re-dispatch or re-lock when already in-flight on this replica'() {
        given:
        svc.registerHandler(new InProcHandler())
        svc.@inFlight.add('c1')
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1'))
        0 * lockManager.tryAcquire(_)
        0 * pool.execute(_)
        !ack
    }

    def 'releases the lock and stays queued when the pool is saturated (backpressure)'() {
        given:
        svc.registerHandler(new InProcHandler())
        def lock = Mock(Lock)
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1'))
        1 * lockManager.tryAcquire(_) >> lock
        1 * pool.execute(_) >> { throw new RejectedExecutionException('full') }
        1 * lock.release()
        !ack
        !svc.@inFlight.contains('c1')
    }

    // ---- runCommand (worker thread) ----

    def 'runCommand: EXECUTE marks RUNNING then SUCCEEDED, clears in-flight and releases lock'() {
        given:
        def reg = CommandRegistration.of(new InProcHandler())
        def lock = Mock(Lock)
        svc.@inFlight.add('c1')
        def st = submitted('c1')
        when:
        svc.runCommand('c1', reg, lock, false)
        then:
        2 * store.findById('c1') >>> [Optional.of(st), Optional.of(st.started())]
        1 * store.save({ it.status() == CommandStatus.RUNNING })
        1 * store.save({ it.status() == CommandStatus.SUCCEEDED })
        1 * lock.release()
        !svc.@inFlight.contains('c1')
    }

    def 'runCommand: a RUNNING result leaves the command RUNNING with no terminal write'() {
        given:
        def h = new ExternalJobHandler()
        def reg = CommandRegistration.of(h)
        def lock = Mock(Lock)
        svc.@inFlight.add('c1')
        when:
        svc.runCommand('c1', reg, lock, false)
        then:
        _ * store.findById('c1') >> Optional.of(submitted('c1'))
        1 * store.save({ it.status() == CommandStatus.RUNNING })
        0 * store.save({ it.status() == CommandStatus.SUCCEEDED })
        0 * store.save({ it.status() == CommandStatus.FAILED })
        1 * lock.release()
        h.executeCalled
        !svc.@inFlight.contains('c1')
    }

    def 'runCommand: CHECK_STATUS polls checkStatus and applies a terminal result'() {
        given:
        def h = new ExternalJobHandler()
        def reg = CommandRegistration.of(h)
        def lock = Mock(Lock)
        def rs = running('c1')
        when:
        svc.runCommand('c1', reg, lock, true)
        then:
        _ * store.findById('c1') >> Optional.of(rs)
        0 * store.save({ it.status() == CommandStatus.RUNNING })
        1 * store.save({ it.status() == CommandStatus.SUCCEEDED })
        1 * lock.release()
        h.checkStatusCalled
        !h.executeCalled
    }

    def 'runCommand: a throwing handler marks FAILED and releases the lock'() {
        given:
        def reg = CommandRegistration.of(new FailingHandler())
        def lock = Mock(Lock)
        svc.@inFlight.add('c1')
        def st = submitted('c1')
        when:
        svc.runCommand('c1', reg, lock, false)
        then:
        2 * store.findById('c1') >>> [Optional.of(st), Optional.of(st.started())]
        1 * store.save({ it.status() == CommandStatus.RUNNING })
        1 * store.save({ it.status() == CommandStatus.FAILED && it.error() == 'boom' })
        1 * lock.release()
        !svc.@inFlight.contains('c1')
    }

    def 'runCommand: does not clobber a CANCELLED observed at the terminal re-read'() {
        given:
        def reg = CommandRegistration.of(new InProcHandler())
        def lock = Mock(Lock)
        def st = submitted('c1')
        when:
        svc.runCommand('c1', reg, lock, false)
        then:
        2 * store.findById('c1') >>> [Optional.of(st), Optional.of(st.cancelled())]
        1 * store.save({ it.status() == CommandStatus.RUNNING })
        0 * store.save({ it.status() == CommandStatus.SUCCEEDED })
        1 * lock.release()
    }

    def 'runCommand: skips execution when already terminal before start'() {
        given:
        def reg = CommandRegistration.of(new InProcHandler())
        def lock = Mock(Lock)
        svc.@inFlight.add('c1')
        when:
        svc.runCommand('c1', reg, lock, false)
        then:
        1 * store.findById('c1') >> Optional.of(submitted('c1').cancelled())
        0 * store.save(_)
        1 * lock.release()
        !svc.@inFlight.contains('c1')
    }

    // ---- B1 ordering: in-flight registered before dispatch (no leak) ----

    def 'no in-flight leak when the worker completes synchronously during dispatch'() {
        given: 'an inline executor that runs the task within pool.execute() (worker finishes before processCommand returns)'
        svc.registerHandler(new InProcHandler())
        def lock = Mock(Lock)
        svc.@pool = { Runnable r -> r.run() } as ExecutorService
        def st = submitted('c1')
        when:
        def ack = svc.processCommand(CommandMsg.of('c1', 'test'))
        then:
        _ * store.findById('c1') >> Optional.of(st)
        1 * lockManager.tryAcquire(_) >> lock
        1 * lock.release()
        !ack
        // add-before-submit ordering means the worker's finally removed the entry it saw registered
        !svc.@inFlight.contains('c1')
    }

    // ---- pool lifecycle ----

    def 'stop() shuts down and terminates the worker pool'() {
        given:
        svc.@queue = Mock(CommandQueue)
        when:
        svc.start()
        then:
        svc.@pool != null
        when:
        svc.stop()
        then:
        svc.@pool.isShutdown()
        svc.@pool.isTerminated()
    }

    // ---- handler discrimination ----

    def 'detects whether a handler overrides checkStatus'() {
        expect:
        CommandServiceImpl.overridesCheckStatus(new ExternalJobHandler())
        !CommandServiceImpl.overridesCheckStatus(new InProcHandler())
    }
}

// ---- test handlers ----

class InProcHandler implements CommandHandler<TestParams, TestResult> {
    @Override String type() { 'test' }
    @Override CommandResult<TestResult> execute(Command<TestParams> command) {
        return CommandResult.success(new TestResult('done', command.params().value))
    }
}

class ExternalJobHandler implements CommandHandler<TestParams, TestResult> {
    boolean executeCalled
    boolean checkStatusCalled
    @Override String type() { 'test' }
    @Override CommandResult<TestResult> execute(Command<TestParams> command) {
        executeCalled = true
        return CommandResult.running()
    }
    @Override CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
        checkStatusCalled = true
        return CommandResult.success(new TestResult('slow', command.params().value))
    }
}

class FailingHandler implements CommandHandler<TestParams, TestResult> {
    @Override String type() { 'test' }
    @Override CommandResult<TestResult> execute(Command<TestParams> command) {
        throw new RuntimeException('boom')
    }
}
