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

import java.util.concurrent.atomic.AtomicInteger

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import jakarta.inject.Inject
import org.junit.jupiter.api.TestInstance
import spock.lang.Shared
import spock.lang.Specification

/**
 * Covers the PROCESSING re-poll cadence: a handler-declared PROCESSING command is re-polled
 * on {@code checkStatusInterval} — decoupled from the visibility timeout (crash detection) and
 * from the transient-error retry cadence. Without the decoupling, shortening the visibility timeout
 * for faster crash recovery silently multiplies the checkStatus() load on every
 * downstream dependency the polls touch (PR #913 review).
 *
 * @author Paolo Di Tommaso
 */
@MicronautTest(packages = ["io.seqera.data.workqueue"], transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandServiceCheckStatusIntervalTest extends Specification implements TestPropertyProvider {

    @Inject
    @Shared
    CommandService commandService

    static final AtomicInteger polls = new AtomicInteger()

    @Override
    Map<String, String> getProperties() {
        return [
            'command-queue.poll-interval' : '50ms',
            'workqueue.local.retry-delay' : '50ms',    // plain retries stay fast...
            'command.check-status-interval'      : '600ms',   // ...but PROCESSING re-polls pace on the cadence
        ]
    }

    def setupSpec() {
        commandService.registerHandler(new CommandHandler<TestParams, TestResult>() {
            @Override
            String type() { 'poll-forever' }
            @Override
            CommandResult<TestResult> execute(Command<TestParams> command) { CommandResult.processing() }
            @Override
            CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
                polls.incrementAndGet()
                return CommandResult.processing()
            }
        })
        commandService.start()
    }

    def cleanupSpec() {
        commandService.stop()
    }

    def 'a running command should be re-polled on the check-status interval, not the retry delay'() {
        given:
        def command = new TestCommand(TsidCreator.getTsid().toLowerCase(), 'poll-forever', new TestParams(1, 'x'))

        when: 'the command declares PROCESSING and is observed over a 2s window'
        commandService.submit(command)
        sleep 2_000

        then: 'polls are paced by the 600ms cadence - not the 50ms retry delay (which would give ~30)'
        polls.get() <= 5
        polls.get() >= 1
    }

}
