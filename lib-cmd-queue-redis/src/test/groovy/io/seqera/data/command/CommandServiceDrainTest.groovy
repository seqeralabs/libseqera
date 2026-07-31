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
/**
 * Covers {@code drain()}: an execution abandoned by the execute-timeout keeps running in the
 * background, and a shutdown must wait for it. That window is where a handler is still writing
 * to a database whose connection pool is about to be closed.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
// rebuildContext: drain() releases the queue for good, so each feature needs its own
// CommandService rather than a context shared across the class
@MicronautTest(packages = ["io.seqera.data.stream"], transactional = false, rebuildContext = true)
class CommandServiceDrainTest extends Specification implements TestPropertyProvider {

    @Inject
    CommandService commandService

    @Inject
    CommandStateStore store

    @Override
    Map<String, String> getProperties() {
        return [
            'command-queue.poll-interval'  : '100ms',
            // deliberately shorter than the handler below, so execute() is abandoned mid-flight
            // exactly as it is in production, where batches outlive the 1s default
            'command-queue.execute-timeout': '200ms'
        ]
    }

    def 'drain should wait for a handler abandoned by the execute timeout'() {
        given:
        def handler = new SlowHandler(runFor: 1_500)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'slow-drain', new TestParams(1, 'x'))

        when: 'the command is picked up and overruns the execute timeout'
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)

        then: 'it is counted as in flight even though execute() already returned to the dispatcher'
        commandService.activeCommands() == 1

        when:
        def drained = commandService.drain(Duration.ofSeconds(10))

        then: 'drain blocked until the handler finished, and it was never interrupted'
        drained
        handler.completed.get()
        !handler.interrupted.get()
        commandService.activeCommands() == 0
    }

    def 'drain should report false and leave the count visible when the handler outlives the timeout'() {
        given:
        def handler = new SlowHandler(runFor: 3_000)
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

        cleanup: 'let the abandoned handler finish so it does not outlive the test'
        handler.finished.await(10, TimeUnit.SECONDS)
    }

    def 'drain should be a no-op when the service was never started'() {
        expect:
        commandService.drain(Duration.ofSeconds(1))
        commandService.activeCommands() == 0
    }

    static class SlowHandler implements CommandHandler<TestParams, TestResult> {
        long runFor
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
            return CommandResult.running()
        }
    }

}
