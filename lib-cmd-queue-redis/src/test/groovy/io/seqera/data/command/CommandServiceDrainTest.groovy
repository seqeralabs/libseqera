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

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.seqera.data.command.store.CommandStateStore
import jakarta.inject.Inject

import spock.lang.Specification
import spock.util.concurrent.PollingConditions
/**
 * Covers {@code drain()} under task-settled delivery: every handler invocation — execute()
 * calls AND checkStatus() polls — runs as a counted task on the blocking executor, and a
 * shutdown must wait for all of them. That window is where a handler is still writing to a
 * database whose connection pool is about to be closed.
 *
 * <p>checkStatus() polls used to run uncounted on the dispatcher thread; the message-lease
 * model counts them in {@code inflight}, so drain no longer abandons an in-flight poll —
 * the delta called out in docs/command-execution-guarantee-message-lease.md.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
// rebuildContext: drain() releases the queue for good, so each feature needs its own
// CommandService rather than a context shared across the class
@MicronautTest(packages = ["io.seqera.data.workqueue"], transactional = false, rebuildContext = true)
class CommandServiceDrainTest extends Specification implements TestPropertyProvider {

    @Inject
    CommandService commandService

    @Inject
    CommandStateStore store

    @Override
    Map<String, String> getProperties() {
        return [
            'command-queue.poll-interval': '100ms'
        ]
    }

    def 'drain should wait for a handler task still running on the executor'() {
        given:
        def handler = new SlowHandler(runFor: 1_500)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'slow-drain', new TestParams(1, 'x'))

        when: 'the command is dispatched and its task keeps running past the DEFERRED hand-off'
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)

        then: 'it is counted as in flight even though the dispatcher already moved on'
        commandService.activeCommands() == 1

        when:
        def drained = commandService.drain(Duration.ofSeconds(10))

        then: 'drain blocked until the handler finished, and it was never interrupted'
        drained
        handler.completed.get()
        !handler.interrupted.get()
        commandService.activeCommands() == 0
    }

    def 'drain should report incomplete and leave the count visible when the handler outlives the budget'() {
        given: 'the handler is held open by the test, so the count cannot race the assertion'
        def release = new CountDownLatch(1)
        def handler = new SlowHandler(release: release)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'slow-drain', new TestParams(2, 'x'))

        when:
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)
        def drained = commandService.drain(Duration.ofMillis(300))

        then: 'the caller is told the drain was incomplete rather than silently proceeding'
        !drained
        commandService.activeCommands() == 1

        and: '''the survivor is identified, not merely counted: a drain that expires on a slow cloud
                call is an expected shape, and only the id and type tell that apart from something
                unexpected'''
        commandService.activeCommandDetails() == ["${command.id()}(slow-drain)".toString()]

        cleanup: 'let the still-running handler finish so it does not outlive the test'
        release.countDown()
        handler.finished.await(10, TimeUnit.SECONDS)
    }

    def 'should name an in-flight command by id and type, and forget it once the task ends'() {
        given: 'the handler is held open by the test, so the register cannot race the assertion'
        def release = new CountDownLatch(1)
        def handler = new SlowHandler(release: release)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'slow-drain', new TestParams(6, 'x'))

        expect: 'an idle service names nothing'
        commandService.activeCommandDetails() == []

        when:
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)

        then:
        commandService.activeCommandDetails() == ["${command.id()}(slow-drain)".toString()]

        when: 'the handler is released and its task returns'
        release.countDown()
        handler.finished.await(10, TimeUnit.SECONDS)

        then: '''the entry is unregistered — a leak here would name a phantom command in every
                 later shutdown report, exactly when a responder can least afford a false lead'''
        new PollingConditions(timeout: 10).eventually {
            assert commandService.activeCommandDetails() == []
            assert commandService.activeCommands() == 0
        }

        cleanup:
        commandService.drain(Duration.ofSeconds(10))
    }

    def 'should report several in-flight commands in a stable order'() {
        given: 'two commands whose handler invocations are both held open by the test'
        def release = new CountDownLatch(1)
        def handler = new SlowHandler(release: release)
        commandService.registerHandler(handler)
        commandService.start()

        and: 'ids known in lexical order, so the expected report is unambiguous'
        def ids = [TsidCreator.getTsid().toLowerCase(), TsidCreator.getTsid().toLowerCase()].sort()

        when: '''the lexically LARGER id is submitted first: the register hands out keys in
                 submission order, so an unsorted report would come back the wrong way round'''
        commandService.submit(new TestCommand(ids[1], 'slow-drain', new TestParams(7, 'x')))
        commandService.submit(new TestCommand(ids[0], 'slow-drain', new TestParams(8, 'x')))

        then: 'a human reading the shutdown log gets the same order every time'
        new PollingConditions(timeout: 10).eventually {
            assert commandService.activeCommandDetails() == ["${ids[0]}(slow-drain)".toString(), "${ids[1]}(slow-drain)".toString()]
        }

        cleanup:
        release.countDown()
        handler.finished.await(10, TimeUnit.SECONDS)
        commandService.drain(Duration.ofSeconds(10))
    }

    def 'drain should not exceed its budget when in-flight work never finishes'() {
        given: 'a handler that outlives the whole drain budget'
        def handler = new SlowHandler(runFor: 5_000)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'slow-drain', new TestParams(3, 'x'))

        when:
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)
        def begin = System.currentTimeMillis()
        commandService.drain(Duration.ofMillis(500))
        def elapsed = System.currentTimeMillis() - begin

        then: '''close() must be bounded by what is left of drain's budget, not start its own timer.
                 Before this fix it added a further closeTimeout() (10s) plus a 1s join, which would
                 blow past a container graceful-shutdown grace period and get us hard-stopped
                 mid-drain — the very thing draining exists to avoid.'''
        elapsed < 3_000

        cleanup:
        handler.finished.await(10, TimeUnit.SECONDS)
    }

    def 'drain should count and wait for an in-flight checkStatus poll'() {
        given: 'a PROCESSING command whose status poll blocks on the executor'
        def release = new CountDownLatch(1)
        def handler = new BlockingCheckStatusHandler(release: release)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(
                TsidCreator.getTsid().toLowerCase(),
                'blocking-check-status',
                new TestParams(4, 'x'))

        when:
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)

        then: 'the poll is a counted task, no longer invisible dispatcher work'
        commandService.activeCommands() == 1

        when: 'the poll is released while the drain is waiting on it'
        Thread.start {
            sleep 500
            release.countDown()
        }
        def drained = commandService.drain(Duration.ofSeconds(10))

        then: 'drain waited for the poll to settle instead of abandoning it'
        drained
        handler.finished.await(10, TimeUnit.SECONDS)
        commandService.activeCommands() == 0
    }

    def 'drain should be a no-op when the service was never started'() {
        expect:
        commandService.drain(Duration.ofSeconds(1))
        commandService.activeCommands() == 0
    }

    static class SlowHandler implements CommandHandler<TestParams, TestResult> {
        /** Fixed run time, used when {@link #release} is null. */
        long runFor
        /** When set, the handler blocks until the test releases it — no wall-clock guessing. */
        CountDownLatch release
        final CountDownLatch entered = new CountDownLatch(1)
        final CountDownLatch finished = new CountDownLatch(1)
        final AtomicBoolean completed = new AtomicBoolean(false)
        final AtomicBoolean interrupted = new AtomicBoolean(false)

        @Override
        String type() { 'slow-drain' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            entered.countDown()
            try {
                if (release != null)
                    release.await(30, TimeUnit.SECONDS)
                else
                    Thread.sleep(runFor)
                completed.set(true)
            }
            catch (InterruptedException e) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
            finally {
                finished.countDown()
            }
            return CommandResult.success(new TestResult('done', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            return CommandResult.processing()
        }
    }

    static class BlockingCheckStatusHandler implements CommandHandler<TestParams, TestResult> {
        CountDownLatch release
        final CountDownLatch entered = new CountDownLatch(1)
        final CountDownLatch finished = new CountDownLatch(1)

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            return CommandResult.processing()
        }

        @Override
        String type() { 'blocking-check-status' }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            entered.countDown()
            try {
                release.await(30, TimeUnit.SECONDS)
            }
            finally {
                finished.countDown()
            }
            return CommandResult.success(new TestResult('polled', command.params().value))
        }
    }

}
