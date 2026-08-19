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

package io.seqera.data.workqueue

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.random.LongRndKey
import jakarta.inject.Inject
import spock.lang.Specification
import static io.seqera.data.workqueue.MessageConsumer.Decision.ACK
/**
 * Covers the cooperative shutdown contract: a consumer already running must be allowed to
 * finish, because at that point it may be mid-way through work against resources the caller
 * is about to tear down.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class AbstractWorkQueueDrainTest extends Specification {

    @Inject
    LocalWorkQueue target

    def 'awaitQuiescent should let an in-progress consumer finish without interrupting it'() {
        given:
        def id = "stream-${LongRndKey.rndHex()}"
        def stream = new TestPlainQueue(target)
        and: 'a consumer that is slow enough to still be running when the drain starts'
        def entered = new CountDownLatch(1)
        def completed = new AtomicBoolean(false)
        def interrupted = new AtomicBoolean(false)
        stream.addConsumer(id, { msg, lease ->
            entered.countDown()
            try {
                Thread.sleep(500)
                completed.set(true)
            }
            catch (InterruptedException e) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
            return ACK
        })

        when: 'a message is picked up and the drain begins while the consumer is still inside it'
        stream.offer(id, 'one')
        entered.await(5, TimeUnit.SECONDS)
        def quiesced = stream.awaitQuiescent(Duration.ofSeconds(10))

        then: 'the drain waits for it rather than cutting it short'
        quiesced
        completed.get()
        !interrupted.get()

        cleanup:
        stream.close()
    }

    def 'awaitQuiescent should stop the dispatcher claiming further messages'() {
        given:
        def id = "stream-${LongRndKey.rndHex()}"
        def stream = new TestPlainQueue(target)
        def seen = new AtomicInteger()
        def entered = new CountDownLatch(1)
        stream.addConsumer(id, { msg, lease ->
            seen.incrementAndGet()
            entered.countDown()
            Thread.sleep(300)
            return ACK
        })

        when: 'two messages are queued but the drain starts during the first'
        stream.offer(id, 'one')
        entered.await(5, TimeUnit.SECONDS)
        stream.offer(id, 'two')
        stream.awaitQuiescent(Duration.ofSeconds(10))
        and: 'well past the poll interval, so a live dispatcher would have taken the second'
        Thread.sleep(1_500)

        then: 'only the message already claimed was delivered'
        seen.get() == 1

        cleanup:
        stream.close()
    }

    def 'awaitQuiescent should report false when the consumer outlives the timeout'() {
        given:
        def id = "stream-${LongRndKey.rndHex()}"
        def stream = new TestPlainQueue(target)
        def entered = new CountDownLatch(1)
        stream.addConsumer(id, { msg, lease ->
            entered.countDown()
            Thread.sleep(2_000)
            return ACK
        })

        when:
        stream.offer(id, 'one')
        entered.await(5, TimeUnit.SECONDS)
        def quiesced = stream.awaitQuiescent(Duration.ofMillis(200))

        then: 'the caller is told the drain did not complete, and decides what to do next'
        !quiesced

        cleanup:
        stream.close()
    }

    def 'close should drain cooperatively instead of interrupting the consumer'() {
        given: 'this is the behaviour change - close() used to interrupt the dispatcher first'
        def id = "stream-${LongRndKey.rndHex()}"
        def stream = new TestPlainQueue(target)
        def entered = new CountDownLatch(1)
        def completed = new AtomicBoolean(false)
        def interrupted = new AtomicBoolean(false)
        stream.addConsumer(id, { msg, lease ->
            entered.countDown()
            try {
                Thread.sleep(500)
                completed.set(true)
            }
            catch (InterruptedException e) {
                interrupted.set(true)
                Thread.currentThread().interrupt()
            }
            return ACK
        })

        when:
        stream.offer(id, 'one')
        entered.await(5, TimeUnit.SECONDS)
        stream.close()

        then: 'the consumer ran to completion and was never interrupted'
        completed.get()
        !interrupted.get()
    }

    def 'awaitQuiescent should be a no-op when no consumer was ever registered'() {
        given:
        def stream = new TestPlainQueue(target)

        expect:
        stream.awaitQuiescent(Duration.ofSeconds(1))

        cleanup:
        stream.close()
    }

    def 'a second close should not wait again after the first one gave up'() {
        given: 'a consumer that outlives the first close budget'
        def id = "stream-${LongRndKey.rndHex()}"
        def stream = new TestPlainQueue(target)
        def entered = new CountDownLatch(1)
        stream.addConsumer(id, { msg, lease ->
            entered.countDown()
            Thread.sleep(3_000)
            return ACK
        })

        when:
        stream.offer(id, 'one')
        entered.await(5, TimeUnit.SECONDS)
        stream.close(Duration.ofMillis(200))
        and: 'the @PreDestroy backstop closes again, with its own larger default budget'
        def begin = System.currentTimeMillis()
        stream.close()
        def elapsed = System.currentTimeMillis() - begin

        then: 'the shutdown budget was spent once - the second close must not spend it again'
        elapsed < 1_000
    }

}
