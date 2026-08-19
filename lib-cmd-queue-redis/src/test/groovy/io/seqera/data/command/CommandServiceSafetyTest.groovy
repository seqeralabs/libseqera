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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

import io.seqera.data.command.store.CommandStateStore
import io.seqera.data.workqueue.MessageConsumer
import io.seqera.data.workqueue.MessageLease
import spock.lang.Specification
/**
 * Guards the silent-failure seams in command processing, plus the cooperative refusal that keeps a
 * shutdown bounded.
 *
 * <p>The in-flight counter must never leak: a permanently over-counted value makes every later
 * {@code drain()} report false, the readiness indicator show phantom activeTasks forever, and the
 * admission cap starve the dispatcher.
 *
 * <p>The PROCESSING mark declared by a handler must not be silently skipped: a redelivery with the
 * state still PENDING starts a second execution while the async work it declared is in flight.
 *
 * <p>And a delivery claimed just before a shutdown must not be routed at all — starting work the
 * shutdown budget cannot wait out is the failure lever 1 of #955 closes — while a stale delivery is
 * still acked and a restarted service resumes routing.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CommandServiceSafetyTest extends Specification {

    def 'a rejected handler submission should settle as RETRY without leaking the in-flight count'() {
        given: 'an executor already shut down, as during a non-graceful teardown'
        def store = Mock(CommandStateStore)
        def executor = Mock(ExecutorService)
        def lease = Mock(MessageLease)
        def service = new CommandServiceImpl(store: store, executor: executor)
        service.registerHandler(new TestCommandHandler())
        def state = CommandState.create('cmd-reject', 'test', new TestParams(1, 'fast'))

        when:
        def decision = service.processCommand(CommandMsg.of('cmd-reject', 'test'), lease)

        then:
        1 * store.findById('cmd-reject') >> Optional.of(state)
        1 * executor.submit(_) >> { throw new RejectedExecutionException('shutting down') }

        and: 'nothing ran and nothing owns the lease: the claim cycle re-delivers'
        decision == MessageConsumer.Decision.RETRY
        0 * lease._
        service.activeCommands() == 0

        and: '''nor does the rejection leak the entry: one stranded here would name a command that
                never ran in every later shutdown report AND hold the admission cap down forever'''
        service.activeCommandDetails() == []
    }

    def 'a throwing task should decrement the in-flight count exactly once'() {
        given: 'a real executor, so the task genuinely runs and its finally-decrement fires'
        def executor = Executors.newSingleThreadExecutor()
        def service = new CommandServiceImpl(executor: executor)

        when: 'the failure surfaces via the future, never out of submit(), so only one site decrements'
        def future = service.submitCounted({ throw new IllegalStateException('boom') } as Runnable, 'cmd-boom(test)')
        future.get(5, TimeUnit.SECONDS)

        then:
        thrown(ExecutionException)
        and: '''the entry is removed on the throwing path too, not only on a clean return — and since
                the count IS the register, a leak here would inflate both at once'''
        service.activeCommands() == 0
        service.activeCommandDetails() == []

        cleanup:
        executor.shutdownNow()
    }

    def 'a drain should report what was in flight at its deadline, not after the stream closed'() {
        given: '''a task that is still running when the budget expires and finishes while the stream is
                  being released. close() gets whatever is left of the budget, so that window is real —
                  and reading the register after it would tell the caller the drain succeeded'''
        def release = new CountDownLatch(1)
        def finished = new CountDownLatch(1)
        def executor = Executors.newSingleThreadExecutor()
        def queue = Mock(CommandQueue)
        def service = new CommandServiceImpl(queue: queue, executor: executor, config: Stub(CommandConfig))

        and: 'the handler task blocks until close() lets it go'
        service.start()
        service.submitCounted({ release.await(10, TimeUnit.SECONDS); finished.countDown() } as Runnable, 'cmd-slow(test)')

        when:
        def drained = service.drain(Duration.ofMillis(200))

        then: 'the dispatcher stopped cleanly, so only the in-flight task can make this incomplete'
        1 * queue.awaitQuiescent(_) >> true

        and: 'the task finishes DURING close, exactly the window a post-close read would miss'
        1 * queue.close(_) >> {
            release.countDown()
            finished.await(10, TimeUnit.SECONDS)
        }

        and: '''the caller is told the truth about its deadline: work outlived the budget, even though
                nothing is in flight by the time drain() returns'''
        !drained

        cleanup:
        executor.shutdownNow()
    }

    def 'the PROCESSING mark should be retried once when the first write is contended'() {
        given:
        def store = Mock(CommandStateStore)
        def service = new CommandServiceImpl(store: store)
        def state = CommandState.create('cmd-mark', 'test', new TestParams(2, 'fast'))

        when:
        service.markProcessing(state)

        then: 'the first write is contended, the retry lands, and no status lookup is needed'
        2 * store.update('cmd-mark', _) >>> [false, true]
        0 * store.findById(_)
    }

    def 'a persistently missed PROCESSING mark should be checked against the terminal guard'() {
        given:
        def store = Mock(CommandStateStore)
        def service = new CommandServiceImpl(store: store)
        def state = CommandState.create('cmd-miss', 'test', new TestParams(3, 'fast'))

        when:
        service.markProcessing(state)

        then: 'both writes fail, so the status is looked up to tell contention from cancellation'
        2 * store.update('cmd-miss', _) >> false
        1 * store.findById('cmd-miss') >> Optional.of(state)
    }

    def 'a refused result write should ack once the command is verified terminal'() {
        given: 'a handler task whose terminal result write is refused because a cancel won underneath'
        def store = Mock(CommandStateStore)
        def lease = Mock(MessageLease)
        def service = new CommandServiceImpl(store: store)
        def params = new TestParams(1, 'fast')
        def state = CommandState.create('cmd-refused-terminal', 'test', params)
        def command = new TestCommand('cmd-refused-terminal', 'test', params)

        when:
        service.runCommand(command, state, new TestCommandHandler(), lease)

        then: 'the refusal is verified against the store, not assumed'
        1 * store.update('cmd-refused-terminal', _) >> false
        1 * store.findById('cmd-refused-terminal') >> Optional.of(state.cancelled())
        and: 'the stale message settles now, not one redelivery later'
        1 * lease.ack()
    }

    def 'a delivery claimed just before a drain should not be routed to the handler'() {
        given: '''the window lever 1 of #955 closes: the dispatcher claimed this message just before
                  the drain began, and the handler is about to start a cloud call whose own timeout
                  is longer than the whole drain budget'''
        def store = Mock(CommandStateStore)
        def lease = Mock(MessageLease)
        def handler = Mock(CommandHandler)
        def params = new TestParams(1, 'fast')
        def state = CommandState.create('cmd-draining', 'test', params)
        def command = new TestCommand('cmd-draining', 'test', params)
        def service = new CommandServiceImpl(store: store)

        when: '''the shutdown is signalled through the real entry point, not by setting the flag:
                 that is what proves drain() arms the refusal. Never started, so drain() takes its
                 early return and waits for nothing'''
        service.drain(Duration.ofSeconds(1))
        service.runCommand(command, state, handler, lease)

        then: 'the handler is never entered, so the drain has nothing new to wait out'
        0 * handler.execute(_)

        and: 'no state is written: there is nothing to roll back, and nothing to explain later'
        0 * store._

        and: '''settled for redelivery, never acked — a live command whose only message was acked
                would be stranded with nothing left to advance it'''
        (1.._) * lease.retry()
        0 * lease.ack()
    }

    def 'a PROCESSING delivery should not be polled once stop() has signalled the shutdown'() {
        given: 'a command whose handler declared async work, so this delivery routes to checkStatus'
        def store = Mock(CommandStateStore)
        def lease = Mock(MessageLease)
        def handler = Mock(CommandHandler)
        def params = new TestParams(2, 'fast')
        def state = CommandState.create('cmd-draining-poll', 'test', params).toProcessing()
        def command = new TestCommand('cmd-draining-poll', 'test', params)
        def service = new CommandServiceImpl(store: store)

        when: 'stop() is the other arming point, and it signals even when it has nothing to stop'
        service.stop()
        service.runCommand(command, state, handler, lease)

        then: '''the poll is refused too, not only execute(): a poll can reach a cloud call of its
                 own, and the guard sits before the routing switch precisely so no status decides it'''
        0 * handler.checkStatus(_, _)
        0 * handler.execute(_)
        and:
        (1.._) * lease.retry()
        0 * lease.ack()
    }

    def 'a terminal delivery should still be acked while shutting down'() {
        given: 'a stale message for a command that already completed'
        def store = Mock(CommandStateStore)
        def lease = Mock(MessageLease)
        def handler = Mock(CommandHandler)
        def params = new TestParams(3, 'fast')
        def state = CommandState.create('cmd-draining-stale', 'test', params).completed('done')
        def command = new TestCommand('cmd-draining-stale', 'test', params)
        def service = new CommandServiceImpl(store: store)

        when:
        service.drain(Duration.ofSeconds(1))
        service.runCommand(command, state, handler, lease)

        then: '''the shutdown check sits AFTER the terminal branch on purpose: leaving a dead entry
                 to be redelivered into the next process buys nothing, and acking it costs nothing'''
        1 * lease.ack()
        0 * handler.execute(_)
        0 * handler.checkStatus(_, _)
    }

    def 'a service started again should route deliveries once more'() {
        given: 'a service that has been stopped, so the shutdown signal is set'
        def store = Mock(CommandStateStore)
        def queue = Mock(CommandQueue)
        def lease = Mock(MessageLease)
        def handler = Mock(CommandHandler)
        def params = new TestParams(4, 'fast')
        def state = CommandState.create('cmd-restarted', 'test', params)
        def command = new TestCommand('cmd-restarted', 'test', params)
        def service = new CommandServiceImpl(store: store, queue: queue)

        when:
        service.start()
        service.stop()
        service.start()
        service.runCommand(command, state, handler, lease)

        then: '''the signal is cleared on start, not latched for the life of the JVM — a service
                 that refused work forever after one stop() would process nothing at all'''
        1 * handler.execute(_) >> CommandResult.success(new TestResult('ok', 4))

        and: 'the invocation completes cleanly through the terminal-result path'
        1 * store.update('cmd-restarted', _) >> true
        1 * lease.ack()
    }

    def 'a refused result write against a live command should retry, never read as terminal'() {
        given: 'the CAS bound exhausted while the command stays non-terminal - NOT a cancel'
        def store = Mock(CommandStateStore)
        def lease = Mock(MessageLease)
        def service = new CommandServiceImpl(store: store)
        def params = new TestParams(2, 'fast')
        def state = CommandState.create('cmd-refused-live', 'test', params)
        def command = new TestCommand('cmd-refused-live', 'test', params)

        when:
        service.runCommand(command, state, new TestCommandHandler(), lease)

        then: 'the re-read shows a live command: retried via redelivery, never acked'
        1 * store.update('cmd-refused-live', _) >> false
        1 * store.findById('cmd-refused-live') >> Optional.of(state.toProcessing())
        and:
        (1.._) * lease.retry()
        0 * lease.ack()
    }

}
