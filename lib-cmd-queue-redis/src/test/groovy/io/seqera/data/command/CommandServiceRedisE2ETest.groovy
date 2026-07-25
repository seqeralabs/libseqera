/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.seqera.data.command

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import io.micronaut.context.ApplicationContext
import io.seqera.fixtures.redis.RedisTestContainer
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class CommandServiceRedisE2ETest extends Specification implements RedisTestContainer {

    def 'should execute a long command once across two replicas and persist terminal state'() {
        given:
        def properties = ['redis.uri': "redis://${redisHostName}:${redisPort}".toString()]
        def ctxA = ApplicationContext.run(properties, 'test', 'redis')
        def ctxB = ApplicationContext.run(properties, 'test', 'redis')
        def serviceA = ctxA.getBean(CommandService)
        def serviceB = ctxB.getBean(CommandService)
        def calls = new AtomicInteger()
        def started = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def handler = new RedisOnceHandler(calls, started, release)
        def id = "redis-e2e-${UUID.randomUUID()}"
        def command = new TestCommand(id, 'redis-once', new TestParams(1, 'redis'))

        when:
        serviceA.registerHandler(handler)
        serviceB.registerHandler(handler)
        serviceA.start()
        serviceB.start()
        serviceA.submit(command)
        serviceB.submit(command) // duplicate submission is transport-level at-least-once

        then:
        started.await(5, TimeUnit.SECONDS)

        when: 'the handler remains active for longer than the one-second claim timeout'
        sleep 2_500

        then: 'neither the Stream lease nor the command-state guard permits overlap'
        calls.get() == 1

        when:
        release.countDown()

        then:
        new PollingConditions(timeout: 8).eventually {
            assert serviceA.getState(id).orElseThrow().status() == CommandStatus.SUCCEEDED
        }
        calls.get() == 1

        cleanup:
        release.countDown()
        serviceA?.stop()
        serviceB?.stop()
        ctxA?.close()
        ctxB?.close()
    }
}

class RedisOnceHandler implements CommandHandler<TestParams, TestResult> {
    private final AtomicInteger calls
    private final CountDownLatch started
    private final CountDownLatch release

    RedisOnceHandler(AtomicInteger calls, CountDownLatch started, CountDownLatch release) {
        this.calls = calls
        this.started = started
        this.release = release
    }

    @Override
    String type() { 'redis-once' }

    @Override
    CommandResult<TestResult> execute(Command<TestParams> command) {
        calls.incrementAndGet()
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
        return CommandResult.success(new TestResult('done', command.params().value))
    }
}
