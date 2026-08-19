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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.context.ApplicationContext
import io.seqera.data.command.store.CommandStateStoreImpl
import io.seqera.data.workqueue.WorkQueue
import io.seqera.data.workqueue.redis.RedisWorkQueue
import io.seqera.data.workqueue.redis.RedisWorkQueueConfig
import io.seqera.data.store.state.impl.RedisStateProvider
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.serde.jackson.JacksonEncodingStrategy
import redis.clients.jedis.JedisPool
import spock.lang.Specification
/**
 * Container proof of the queue layer under the message-lease model
 * (docs/command-execution-guarantee-message-lease.md), against a real Redis with a
 * SHORT visibility timeout: a handler outliving the visibility timeout is never executed twice
 * across replicas, a crashed replica's command is re-executed elsewhere, PROCESSING is
 * only ever the handler's own declaration polled on the claim cadence, a throwing
 * handler is retried with the error streak visible, and the admission cap bounds
 * concurrent handler tasks.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CommandServiceLeaseRedisTest extends Specification implements RedisTestContainer {

    static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(1)

    static class ShortVisibilityQueueConfig implements RedisWorkQueueConfig {
        @Override
        String getDefaultConsumerGroupName() { 'cmd-lease-test-group' }
        @Override
        Duration getVisibilityTimeout() { VISIBILITY_TIMEOUT }
        @Override
        Duration getConsumerWarnTimeout() { Duration.ofSeconds(10) }
    }

    static class LeaseCommandConfig implements CommandConfig {
        int cap = 25
        @Override
        Duration pollInterval() { Duration.ofMillis(100) }
        @Override
        int maxConcurrency() { cap }
        @Override
        Duration stateTtl() { Duration.ofHours(1) }
        @Override
        Duration checkStatusInterval() { VISIBILITY_TIMEOUT }   // at the floor: re-polls on the claim cadence
    }

    /** Queue with a per-test name, so each feature gets its own Redis stream. */
    static class LeaseTestQueue extends CommandQueue {
        private final String qname
        LeaseTestQueue(WorkQueue<String> target, String qname) {
            super(target)
            this.qname = qname
        }
        @Override
        protected String name() { qname }
        @Override
        protected Duration pollInterval() { Duration.ofMillis(100) }
    }

    /** One scheduler replica: its own queue consumer, dispatcher and executor, shared Redis. */
    static class Replica {
        RedisWorkQueue redisQueue
        CommandQueue queue
        CommandServiceImpl service
        ExecutorService executor

        /** Simulate a replica death: the dispatcher stops claiming and heartbeats stop. */
        void kill() {
            queue.awaitQuiescent(Duration.ofSeconds(5))
            redisQueue.@renewalScheduler.shutdownNow()
        }

        void shutdown() {
            service.stop()
            redisQueue.@renewalScheduler.shutdownNow()
            executor.shutdownNow()
        }
    }

    ApplicationContext context
    CommandStateStoreImpl store
    List<Replica> replicas = []

    def setup() {
        context = ApplicationContext.run('test', 'redis')
        sleep(500) // workaround to wait for Redis connection, as in RedisStateProviderTest upstream
        store = new CommandStateStoreImpl(
                context.getBean(RedisStateProvider),
                new JacksonEncodingStrategy<CommandState>() {},
                new LeaseCommandConfig())
    }

    def cleanup() {
        replicas.each { it.shutdown() }
        replicas.clear()
        context.stop()
    }

    private Replica newReplica(String queueName, LeaseCommandConfig cfg = new LeaseCommandConfig()) {
        final redisQueue = new RedisWorkQueue()
        redisQueue.@pool = context.getBean(JedisPool)
        redisQueue.@config = new ShortVisibilityQueueConfig()
        redisQueue.create()
        final queue = new LeaseTestQueue(redisQueue, queueName)
        final executor = Executors.newCachedThreadPool()
        final service = new CommandServiceImpl(config: cfg, store: store, queue: queue, executor: executor)
        final replica = new Replica(redisQueue: redisQueue, queue: queue, service: service, executor: executor)
        replicas << replica
        return replica
    }

    private static String uniqueName() {
        return "cmd-lease-${TsidCreator.getTsid().toLowerCase()}"
    }

    private static TestCommand command(String type) {
        return new TestCommand(TsidCreator.getTsid().toLowerCase(), type, new TestParams(1, 'x'))
    }

    private CommandState awaitStatus(String commandId, CommandStatus expected, long timeoutMillis = 15_000) {
        final deadline = System.currentTimeMillis() + timeoutMillis
        CommandState state = null
        while (System.currentTimeMillis() < deadline) {
            state = store.findById(commandId).orElse(null)
            if (state?.status() == expected)
                return state
            sleep 100
        }
        return state
    }

    def 'a handler running well past the visibility timeout should execute exactly once across two replicas'() {
        given: 'two replicas sharing the queue, and a handler sleeping 3x the visibility timeout'
        def queueName = uniqueName()
        def executions = new AtomicInteger()
        def handler = new SlowExecuteHandler(executions: executions, runFor: VISIBILITY_TIMEOUT.toMillis() * 3)
        def replica1 = newReplica(queueName)
        def replica2 = newReplica(queueName)
        [replica1, replica2].each {
            it.service.registerHandler(handler)
            it.service.start()
        }

        when: 'a command outliving the visibility timeout is submitted'
        def cmd = command('slow-execute')
        replica1.service.submit(cmd)
        def state = awaitStatus(cmd.id(), CommandStatus.SUCCEEDED)

        then: 'the command completed with the handler result'
        state.status() == CommandStatus.SUCCEEDED

        when: 'a grace period (1.5x the visibility timeout) passes in which a stalled entry would surface as a second execution'
        sleep 1_500

        then: 'the message lease kept the entry invisible - exactly one execution'
        executions.get() == 1
    }

    def 'a command whose execution died with its replica should be re-executed exactly once elsewhere'() {
        given: 'replica1 whose handler blocks forever, replica2 whose handler succeeds'
        def queueName = uniqueName()
        def blockedRelease = new CountDownLatch(1)
        def blockedHandler = new BlockingExecuteHandler(release: blockedRelease)
        def recoveryHandler = new CountingHandler()
        def replica1 = newReplica(queueName)
        def replica2 = newReplica(queueName)
        replica1.service.registerHandler(blockedHandler)
        replica2.service.registerHandler(recoveryHandler)
        replica1.service.start()

        when: 'replica1 claims the command and then dies mid-execute'
        def cmd = command('crash-test')
        replica1.service.submit(cmd)
        blockedHandler.entered.await(10, TimeUnit.SECONDS)
        replica1.kill()

        and: 'replica2 comes up and polls the shared queue'
        replica2.service.start()
        def state = awaitStatus(cmd.id(), CommandStatus.SUCCEEDED)

        then: 'the entry idled out, redelivered, and completed on replica2'
        state.status() == CommandStatus.SUCCEEDED

        and: 'the state was still PENDING, so recovery was execute() again - exactly once'
        recoveryHandler.executions.get() == 1
        recoveryHandler.statusChecks.get() == 0

        cleanup:
        blockedRelease.countDown()
    }

    def 'a handler-declared PROCESSING should be polled via checkStatus on the claim cadence'() {
        given:
        def queueName = uniqueName()
        def handler = new AsyncWorkHandler()
        def replica = newReplica(queueName)
        replica.service.registerHandler(handler)
        replica.service.start()

        when: 'the command is submitted and execute() is still in flight'
        def cmd = command('async-work')
        replica.service.submit(cmd)
        handler.entered.await(10, TimeUnit.SECONDS)

        and: 'the state is sampled for as long as execute() has not returned'
        def samples = []
        while (handler.executeReturnedAt == 0) {
            def status = store.findById(cmd.id()).orElseThrow().status()
            if (handler.executeReturnedAt == 0)
                samples << status
            sleep 100
        }

        then: 'no PROCESSING was fabricated before the handler declared it'
        !samples.isEmpty()
        samples.every { it == CommandStatus.PENDING }

        when:
        def state = awaitStatus(cmd.id(), CommandStatus.SUCCEEDED)

        then: 'the handler declaration was recorded and the poll completed the command'
        state.status() == CommandStatus.SUCCEEDED
        state.startedAt() != null
        handler.statusChecks.get() == 1

        and: 'the checkStatus poll rode the claim cadence, not a busy loop - the idle clock runs from the last heartbeat, up to one renewal period before execute() returned'
        handler.firstCheckAt - handler.executeReturnedAt >= VISIBILITY_TIMEOUT.toMillis() / 2
    }

    def 'an execute that throws twice should be redelivered each claim cycle and finally succeed'() {
        given: 'a handler that throws on the first two attempts'
        def queueName = uniqueName()
        def handler = new FlakyHandler(failures: 2)
        def replica = newReplica(queueName)
        replica.service.registerHandler(handler)
        replica.service.start()

        when: 'the command is submitted and the store is sampled while it retries'
        def cmd = command('flaky-redis')
        replica.service.submit(cmd)
        def maxErrors = 0
        CommandState state = null
        def deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            state = store.findById(cmd.id()).orElse(null)
            maxErrors = Math.max(maxErrors, state ? state.errorsCount() : 0)
            if (state?.status() == CommandStatus.SUCCEEDED)
                break
            sleep 100
        }

        then: 'each throw was retried on the claim cadence until the third attempt succeeded'
        state.status() == CommandStatus.SUCCEEDED
        handler.attempts.get() == 3

        and: 'the error streak was visible while the command retried'
        maxErrors >= 1

        and: 'no PROCESSING was ever fabricated - the state stayed PENDING across throws'
        state.startedAt() == null
    }

    def 'the admission cap should bound concurrent handler tasks while all commands complete'() {
        given: 'a replica capped at 2 in-flight tasks and 5 queued slow commands'
        def queueName = uniqueName()
        def handler = new ConcurrencyTrackingHandler(runFor: 500)
        def replica = newReplica(queueName, new LeaseCommandConfig(cap: 2))
        replica.service.registerHandler(handler)
        replica.service.start()

        when:
        def commands = (1..5).collect { command('concurrency-probe') }
        commands.each { replica.service.submit(it) }
        def states = commands.collect { awaitStatus(it.id(), CommandStatus.SUCCEEDED, 20_000) }

        then: 'all 5 completed'
        states.every { it.status() == CommandStatus.SUCCEEDED }

        and: 'never more than 2 handler invocations ran concurrently'
        handler.maxConcurrent.get() <= 2
        handler.executions.get() == 5
    }

    // ------------------------------------------------------------------
    // handlers
    // ------------------------------------------------------------------

    static class SlowExecuteHandler implements CommandHandler<TestParams, TestResult> {
        AtomicInteger executions
        long runFor

        @Override
        String type() { 'slow-execute' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            executions.incrementAndGet()
            sleep runFor
            return CommandResult.success(new TestResult('slow done', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            return CommandResult.processing()
        }
    }

    static class BlockingExecuteHandler implements CommandHandler<TestParams, TestResult> {
        CountDownLatch release
        final CountDownLatch entered = new CountDownLatch(1)

        @Override
        String type() { 'crash-test' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            entered.countDown()
            release.await(60, TimeUnit.SECONDS)
            return CommandResult.success(new TestResult('too late', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            return CommandResult.processing()
        }
    }

    static class CountingHandler implements CommandHandler<TestParams, TestResult> {
        final AtomicInteger executions = new AtomicInteger()
        final AtomicInteger statusChecks = new AtomicInteger()

        @Override
        String type() { 'crash-test' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            executions.incrementAndGet()
            return CommandResult.success(new TestResult('recovered', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            statusChecks.incrementAndGet()
            return CommandResult.processing()
        }
    }

    static class AsyncWorkHandler implements CommandHandler<TestParams, TestResult> {
        final CountDownLatch entered = new CountDownLatch(1)
        final AtomicInteger statusChecks = new AtomicInteger()
        volatile long executeReturnedAt = 0
        volatile long firstCheckAt = 0

        @Override
        String type() { 'async-work' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            entered.countDown()
            // longer than the old 1s execute-timeout: the queue must NOT lose patience
            sleep 1_200
            executeReturnedAt = System.currentTimeMillis()
            return CommandResult.processing()
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            if (statusChecks.incrementAndGet() == 1)
                firstCheckAt = System.currentTimeMillis()
            return CommandResult.success(new TestResult('async done', command.params().value))
        }
    }

    static class FlakyHandler implements CommandHandler<TestParams, TestResult> {
        int failures
        final AtomicInteger attempts = new AtomicInteger()

        @Override
        String type() { 'flaky-redis' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            final n = attempts.incrementAndGet()
            if (n <= failures)
                throw new RuntimeException("boom-$n")
            return CommandResult.success(new TestResult('finally', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            return CommandResult.processing()
        }
    }

    static class ConcurrencyTrackingHandler implements CommandHandler<TestParams, TestResult> {
        long runFor
        final AtomicInteger current = new AtomicInteger()
        final AtomicInteger maxConcurrent = new AtomicInteger()
        final AtomicInteger executions = new AtomicInteger()

        @Override
        String type() { 'concurrency-probe' }

        @Override
        CommandResult<TestResult> execute(Command<TestParams> command) {
            final now = current.incrementAndGet()
            maxConcurrent.updateAndGet { Math.max(it, now) }
            try {
                sleep runFor
            }
            finally {
                current.decrementAndGet()
            }
            executions.incrementAndGet()
            return CommandResult.success(new TestResult('done', command.params().value))
        }

        @Override
        CommandResult<TestResult> checkStatus(Command<TestParams> command, CommandState state) {
            return CommandResult.processing()
        }
    }

}
