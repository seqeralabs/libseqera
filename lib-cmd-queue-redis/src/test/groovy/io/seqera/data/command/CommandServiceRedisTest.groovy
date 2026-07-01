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
import java.util.concurrent.atomic.AtomicInteger

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.context.ApplicationContext
import io.seqera.data.command.store.CommandStateStore
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.lock.Lock
import io.seqera.lock.LockManager
import redis.clients.jedis.JedisPool
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/**
 * Redis-backed integration tests (Testcontainers) proving the async design's distributed guarantees:
 * non-blocking intake, single concurrent runner across replicas, crash recovery, and ack-on-reclaim.
 */
class CommandServiceRedisTest extends Specification implements RedisTestContainer {

    def setup() {
        // isolate each test: wipe streams, state and locks from any previous test
        def pool = new JedisPool(System.getProperty('redis.host'), Integer.parseInt(System.getProperty('redis.port')))
        try {
            pool.resource.withCloseable { it.flushAll() }
        } finally {
            pool.close()
        }
    }

    /**
     * A replica context bound to the test Redis. Setting {@code redis.uri} selects the Redis state
     * provider (excludes the local one, whose {@code @Requires(missingProperty='redis.uri')} would
     * otherwise make the {@code StateProvider} injection ambiguous).
     */
    private static ApplicationContext newContext() {
        def uri = "redis://${System.getProperty('redis.host')}:${System.getProperty('redis.port')}".toString()
        return ApplicationContext.run(['redis.uri': uri], 'test', 'redis')
    }

    private static String tsid() {
        TsidCreator.getTsid().toLowerCase()
    }

    private static TestCommand cmd(String id, String mode, int value = 1) {
        new TestCommand(id, 'test', new TestParams(value, mode))
    }

    def 'does not block intake: a short command completes while a long one is still running'() {
        given:
        def ctx = newContext()
        def svc = ctx.getBean(CommandService)
        svc.registerHandler(new ModeHandler())
        svc.start()
        def longId = tsid()
        def shortId = tsid()

        when: 'a long in-process command is submitted, then a short one'
        svc.submit(cmd(longId, 'slow'))
        sleep(200)
        svc.submit(cmd(shortId, 'fast', 2))

        then: 'the short command finishes while the long one is still running'
        new PollingConditions(timeout: 8).eventually {
            assert svc.getState(shortId).get().status() == CommandStatus.SUCCEEDED
        }
        svc.getState(longId).get().status() == CommandStatus.RUNNING

        cleanup:
        ctx?.stop()
    }

    def 'runs a command exactly once across two replicas sharing one Redis'() {
        given: 'two independent service instances (replicas) against the same Redis'
        def runs = new AtomicInteger()
        def ctxA = newContext()
        def ctxB = newContext()
        def svcA = ctxA.getBean(CommandServiceImpl)
        def svcB = ctxB.getBean(CommandServiceImpl)
        // instrument each replica's lock so we can prove exclusion was lock-mediated, not just
        // "the message was never redelivered to the other replica" (spec §6.18)
        def lockA = new CountingLockManager(svcA.@lockManager)
        def lockB = new CountingLockManager(svcB.@lockManager)
        svcA.@lockManager = lockA
        svcB.@lockManager = lockB
        // in-process handler (no checkStatus override) sleeping past the 1s claim timeout, so the
        // other replica WILL reclaim the unacked message mid-run; only the single-runner lock can
        // stop it re-executing (each replica has its own, empty, in-flight set)
        svcA.registerHandler(new CountingHandler(runs, 3000))
        svcB.registerHandler(new CountingHandler(runs, 3000))
        svcA.start()
        svcB.start()
        def id = tsid()

        when:
        svcA.submit(cmd(id, 'x'))

        then: 'it completes'
        new PollingConditions(timeout: 20).eventually {
            assert svcA.getState(id).get().status() == CommandStatus.SUCCEEDED
        }

        and: 'execute() ran exactly once despite the cross-replica reclaim'
        sleep(2000)
        runs.get() == 1

        and: 'both replicas attempted the lock and the loser was refused (lock-mediated exclusion)'
        (lockA.tryAcquireCalls.get() + lockB.tryAcquireCalls.get()) >= 2
        (lockA.nullReturns.get() + lockB.nullReturns.get()) >= 1

        cleanup:
        ctxA?.stop()
        ctxB?.stop()
    }

    def 'recovers a command left RUNNING by a crashed replica (no lock held)'() {
        given:
        def runs = new AtomicInteger()
        def ctx = newContext()
        def svc = ctx.getBean(CommandService)
        def store = ctx.getBean(CommandStateStore)
        def queue = ctx.getBean(CommandQueue)
        svc.registerHandler(new CountingHandler(runs, 0))
        svc.start()

        and: 'a command previously marked RUNNING by a since-dead replica, with no lock held'
        def id = tsid()
        store.save(CommandState.submitted(id, 'test', new TestParams(5, 'x')).started())
        queue.submit(CommandMsg.of(id, 'test'))

        expect: 'a fresh delivery re-runs execute() to completion (in-process crash recovery)'
        new PollingConditions(timeout: 12).eventually {
            assert svc.getState(id).get().status() == CommandStatus.SUCCEEDED
        }
        runs.get() == 1

        cleanup:
        ctx?.stop()
    }

    def 'acknowledges (removes) the stream entry after completion via reclaim'() {
        given:
        def ctx = newContext()
        def svc = ctx.getBean(CommandService)
        def queue = ctx.getBean(CommandQueue)
        svc.registerHandler(new CountingHandler(new AtomicInteger(), 0))
        svc.start()
        def id = tsid()

        when:
        svc.submit(cmd(id, 'x'))

        then: 'command completes'
        new PollingConditions(timeout: 12).eventually {
            assert svc.getState(id).get().status() == CommandStatus.SUCCEEDED
        }

        and: 'the lingering unacked entry is reclaimed and removed within ~claimTimeout'
        new PollingConditions(timeout: 12).eventually {
            assert queue.length() == 0
        }

        cleanup:
        ctx?.stop()
    }
}

// ---- integration test handlers ----

class ModeHandler implements CommandHandler<TestParams, TestResult> {
    @Override String type() { 'test' }
    @Override CommandResult<TestResult> execute(Command<TestParams> command) {
        if (command.params().mode == 'slow') {
            Thread.sleep(3000)
        }
        return CommandResult.success(new TestResult('ok', command.params().value))
    }
}

/** LockManager decorator recording how many times tryAcquire ran and how often it was refused. */
class CountingLockManager implements LockManager {
    private final LockManager delegate
    final AtomicInteger tryAcquireCalls = new AtomicInteger()
    final AtomicInteger nullReturns = new AtomicInteger()

    CountingLockManager(LockManager delegate) {
        this.delegate = delegate
    }

    @Override
    Lock tryAcquire(String lockKey) {
        tryAcquireCalls.incrementAndGet()
        def lock = delegate.tryAcquire(lockKey)
        if (lock == null) {
            nullReturns.incrementAndGet()
        }
        return lock
    }

    @Override
    Lock acquire(String lockKey, Duration timeout) {
        return delegate.acquire(lockKey, timeout)
    }
}

class CountingHandler implements CommandHandler<TestParams, TestResult> {
    private final AtomicInteger runs
    private final long sleepMs

    CountingHandler(AtomicInteger runs, long sleepMs) {
        this.runs = runs
        this.sleepMs = sleepMs
    }

    @Override String type() { 'test' }
    @Override CommandResult<TestResult> execute(Command<TestParams> command) {
        runs.incrementAndGet()
        if (sleepMs > 0) {
            Thread.sleep(sleepMs)
        }
        return CommandResult.success(new TestResult('ok', command.params().value))
    }
}
