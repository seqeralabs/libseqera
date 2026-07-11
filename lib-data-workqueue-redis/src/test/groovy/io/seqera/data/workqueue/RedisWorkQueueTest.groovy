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

import io.seqera.random.LongRndKey
import spock.lang.Shared
import spock.lang.Specification

import io.micronaut.context.ApplicationContext
import io.seqera.data.workqueue.redis.RedisWorkQueue
import io.seqera.fixtures.redis.RedisTestContainer

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
        def id1 = "queue-${LongRndKey.rndHex()}"
        def id2 = "queue-${LongRndKey.rndHex()}"
        and:
        def queue = context.getBean(RedisWorkQueue)
        and:
        queue.init(id1)
        queue.init(id2)
        when:
        queue.offer(id1, 'one')
        and:
        queue.offer(id2, 'alpha')
        queue.offer(id2, 'delta')
        queue.offer(id2, 'gamma')

        then:
        queue.consume(id1, { it-> it=='one'})
        and:
        queue.consume(id2, { it-> it=='alpha'})
        queue.consume(id2, { it-> it=='delta'})
        queue.consume(id2, { it-> it=='gamma'})
        and:
        !queue.consume(id2, { it-> assert false /* <-- this should not be invoked */ })
    }

    def 'should offer and consume a value with a failure' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = context.getBean(RedisWorkQueue)
        queue.init(id1)
        when:
        queue.offer(id1, 'alpha')
        queue.offer(id1, 'delta')
        queue.offer(id1, 'gamma')

        then:
        queue.consume(id1, { it-> it=='alpha'})
        and:
        try {
            queue.consume(id1, { it-> throw new RuntimeException("Oops")})
        }
        catch (RuntimeException e) {
            assert e.message == 'Oops'
        }
        and:
        // next message is 'gamma' as expected
        queue.consume(id1, { it-> it=='gamma'})
        and:
        // still nothing
        !queue.consume(id1, { it-> assert false /* <-- this should not be invoked */ })
        and:
        // wait 2 seconds (visibility timeout is 1 sec)
        sleep 2_000
        // now the errored message is available
        queue.consume(id1, { it-> it=='delta'})
        and:
        !queue.consume(id1, { it-> assert false /* <-- this should not be invoked */ })

        when:
        queue.offer(id1, 'something')
        then:
        queue.consume(id1, { it-> it=='something'})
    }

    def 'should validate length method' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"
        def queue = context.getBean(RedisWorkQueue)
        queue.init(id1)

        expect:
        queue.length(id1) == 0

        when:
        queue.offer(id1, 'alpha')
        queue.offer(id1, 'delta')
        queue.offer(id1, 'gamma')
        then:
        queue.length(id1) == 3

        when:
        queue.consume(id1, { it-> true})
        then:
        queue.length(id1) == 2
    }

    def 'should claim messages in round-robin fashion to prevent starvation' () {
        given: 'a queue with multiple messages'
        def queueId = "queue-${LongRndKey.rndHex()}"
        def queue = context.getBean(RedisWorkQueue)
        queue.init(queueId)
        and: 'track which messages are consumed'
        def consumedMessages = Collections.synchronizedList([])

        when: 'add 5 messages to the queue'
        queue.offer(queueId, 'msg-1')
        queue.offer(queueId, 'msg-2')
        queue.offer(queueId, 'msg-3')
        queue.offer(queueId, 'msg-4')
        queue.offer(queueId, 'msg-5')

        and: 'consume all messages but reject them (return false) - simulating RUNNING tasks'
        // First pass - read all messages, reject all (they go to PEL)
        5.times {
            queue.consume(queueId, { msg ->
                consumedMessages << msg
                return false  // reject - message stays in PEL
            })
        }

        then: 'all 5 messages should have been read once'
        consumedMessages.size() == 5
        consumedMessages.containsAll(['msg-1', 'msg-2', 'msg-3', 'msg-4', 'msg-5'])

        when: 'clear tracking and wait for visibility timeout'
        consumedMessages.clear()
        sleep 1500  // visibility timeout is 1 second in test config

        and: 'consume again multiple times - messages should be reclaimed in round-robin'
        // Consume 10 times to verify round-robin (should see each message ~2 times)
        10.times {
            queue.consume(queueId, { msg ->
                consumedMessages << msg
                return false  // keep rejecting
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
