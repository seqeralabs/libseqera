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

package io.seqera.data.workqueue.redis

import io.seqera.random.LongRndKey
import spock.lang.Shared
import spock.lang.Specification

import io.micronaut.context.ApplicationContext
import io.seqera.fixtures.redis.RedisTestContainer
import static io.seqera.data.workqueue.MessageConsumer.Decision.ACK
import static io.seqera.data.workqueue.MessageConsumer.Decision.RETRY

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisWorkQueueTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        context.stop()
    }

    def 'should offer and consume a value' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def id2 = "stream-${LongRndKey.rndHex()}"
        and:
        def stream = context.getBean(RedisWorkQueue)
        and:
        stream.init(id1)
        stream.init(id2)
        when:
        stream.offer(id1, 'one')
        and:
        stream.offer(id2, 'alpha')
        stream.offer(id2, 'delta')
        stream.offer(id2, 'gamma')

        then:
        stream.consume(id1, { it, lease -> assert it=='one'; ACK }) == ACK
        and:
        stream.consume(id2, { it, lease -> assert it=='alpha'; ACK }) == ACK
        stream.consume(id2, { it, lease -> assert it=='delta'; ACK }) == ACK
        stream.consume(id2, { it, lease -> assert it=='gamma'; ACK }) == ACK
        and:
        stream.consume(id2, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
    }

    def 'should offer and consume a value with a failure' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = context.getBean(RedisWorkQueue)
        stream.init(id1)
        when:
        stream.offer(id1, 'alpha')
        stream.offer(id1, 'delta')
        stream.offer(id1, 'gamma')

        then:
        stream.consume(id1, { it, lease -> assert it=='alpha'; ACK }) == ACK
        and:
        try {
            stream.consume(id1, { it, lease -> throw new RuntimeException("Oops") })
        }
        catch (RuntimeException e) {
            assert e.message == 'Oops'
        }
        and:
        // next message is 'gamma' as expected
        stream.consume(id1, { it, lease -> assert it=='gamma'; ACK }) == ACK
        and:
        // still nothing
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null
        and:
        // wait 2 seconds (claim timeout is 1 sec)
        sleep 2_000
        // now the errored message is available
        stream.consume(id1, { it, lease -> assert it=='delta'; ACK }) == ACK
        and:
        stream.consume(id1, { it, lease -> assert false /* <-- this should not be invoked */ }) == null

        when:
        stream.offer(id1, 'something')
        then:
        stream.consume(id1, { it, lease -> assert it=='something'; ACK }) == ACK
    }

    def 'should validate length method' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"
        def stream = context.getBean(RedisWorkQueue)
        stream.init(id1)

        expect:
        stream.length(id1) == 0

        when:
        stream.offer(id1, 'alpha')
        stream.offer(id1, 'delta')
        stream.offer(id1, 'gamma')
        then:
        stream.length(id1) == 3

        when:
        stream.consume(id1, { it, lease -> ACK })
        then:
        stream.length(id1) == 2
    }

    def 'should claim messages in round-robin fashion to prevent starvation' () {
        given: 'a stream with multiple messages'
        def queueId = "stream-${LongRndKey.rndHex()}"
        def stream = context.getBean(RedisWorkQueue)
        stream.init(queueId)
        and: 'track which messages are consumed'
        def consumedMessages = Collections.synchronizedList([])

        when: 'add 5 messages to the stream'
        stream.offer(queueId, 'msg-1')
        stream.offer(queueId, 'msg-2')
        stream.offer(queueId, 'msg-3')
        stream.offer(queueId, 'msg-4')
        stream.offer(queueId, 'msg-5')

        and: 'consume all messages but leave them pending (RETRY) - simulating RUNNING tasks'
        // First pass - read all messages, retry all (they go to PEL)
        5.times {
            stream.consume(queueId, { msg, lease ->
                consumedMessages << msg
                return RETRY  // leave pending - message stays in PEL
            })
        }

        then: 'all 5 messages should have been read once'
        consumedMessages.size() == 5
        consumedMessages.containsAll(['msg-1', 'msg-2', 'msg-3', 'msg-4', 'msg-5'])

        when: 'clear tracking and wait for claim timeout'
        consumedMessages.clear()
        sleep 1500  // claim timeout is 1 second in test config

        and: 'consume again multiple times - messages should be reclaimed in round-robin'
        // Consume 10 times to verify round-robin (should see each message ~2 times)
        10.times {
            stream.consume(queueId, { msg, lease ->
                consumedMessages << msg
                return RETRY  // keep leaving them pending
            })
            sleep 100  // small delay between consumes
        }

        then: 'all messages should be processed fairly (round-robin), not just msg-1 repeatedly'
        // Each message should appear at least once in the 10 consume attempts
        // Without the fix, only msg-1 would be claimed repeatedly
        def uniqueMessages = consumedMessages.toSet()
        uniqueMessages.size() > 1  // more than just the first message

        and: 'verify no single message dominates (starvation prevention)'
        def counts = consumedMessages.countBy { it }
        // No message should have more than 4 occurrences out of 10
        // (with fair round-robin, each should have ~2)
        counts.values().every { it <= 4 }
    }

}
