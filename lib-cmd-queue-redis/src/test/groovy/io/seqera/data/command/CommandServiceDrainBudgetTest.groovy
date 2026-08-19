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
import jakarta.inject.Inject

import spock.lang.Specification

/**
 * Covers how {@code drain()} divides its budget between its two waits.
 *
 * <p>Step 1 waits for the dispatcher to quiesce; step 2 waits for handler tasks already running
 * on the executor. The deadline for step 2 is taken <em>before</em> step 1 runs, so whatever
 * step 1 spends comes out of step 2's share. Handed the whole budget, a dispatcher that never
 * quiesces leaves step 2 with nothing — its loop runs zero iterations and handler tasks
 * mid-flight against the database get no grace at all, which is the one thing the drain exists
 * to provide (#888). Both incomplete drains observed in production have exactly that shape,
 * each reporting {@code dispatcherStopped=false} (#955).
 *
 * <p>{@link StallingCommandQueue} never quiesces, so step 1 always spends everything it is
 * given — which is what makes step 2's remaining share observable.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(packages = ["io.seqera.data.workqueue"], transactional = false, rebuildContext = true)
class CommandServiceDrainBudgetTest extends Specification implements TestPropertyProvider {

    @Inject
    CommandService commandService

    @Override
    Map<String, String> getProperties() {
        return [
            'command-queue.poll-interval'  : '100ms',
            'test.command-queue.stalling'  : 'true'
        ]
    }

    def setup() {
        StallingCommandQueue.FIRST_QUIESCE_BUDGET_MILLIS.set(-1)
        StallingCommandQueue.STOPS_DURING_CLOSE.set(false)
    }

    def 'drain should reserve a share of its budget for the in-flight wait'() {
        given: 'a service with nothing in flight, so only the budget split is under test'
        commandService.registerHandler(new SlowHandler(runFor: 0))
        commandService.start()

        when:
        commandService.drain(Duration.ofSeconds(8))

        then: 'step 1 was capped at a quarter, leaving the rest for the in-flight wait'
        StallingCommandQueue.FIRST_QUIESCE_BUDGET_MILLIS.get() == 2_000
    }

    def 'a dispatcher that never quiesces should not consume the in-flight wait'() {
        given: 'a handler that outlives step 1 but finishes well inside the whole budget'
        def handler = new SlowHandler(runFor: 1_200)
        commandService.registerHandler(handler)
        commandService.start()

        and:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'slow-drain', new TestParams(1, 'x'))

        when: 'the handler is running on the executor before the drain begins'
        commandService.submit(command)
        handler.entered.await(10, TimeUnit.SECONDS)

        and:
        def begin = System.currentTimeMillis()
        def drained = commandService.drain(Duration.ofSeconds(4))
        def elapsed = System.currentTimeMillis() - begin

        then: 'the drain waited for the handler rather than burning the whole budget in step 1'
        handler.completed.get()
        !handler.interrupted.get()
        commandService.activeCommands() == 0
        // ~1.2s once step 2 gets its share; ~4s (the whole budget) when step 1 takes everything
        elapsed < 2_500

        and: 'it still reports incomplete, because the dispatcher never stopped'
        !drained
    }

    def 'a dispatcher that stops during close should be reported as a completed drain'() {
        given: 'a dispatcher that outlives its quiesce budget but stops within the drain budget'
        StallingCommandQueue.STOPS_DURING_CLOSE.set(true)
        commandService.registerHandler(new SlowHandler(runFor: 0))
        commandService.start()

        when:
        def drained = commandService.drain(Duration.ofSeconds(4))

        then: 'slow is not stuck: everything settled inside the budget, so nothing was lost'
        drained
    }

    static class SlowHandler implements CommandHandler<TestParams, TestResult> {
        long runFor
        final CountDownLatch entered = new CountDownLatch(1)
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
            return CommandResult.success(new TestResult('done', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            return CommandResult.processing()
        }
    }

}
