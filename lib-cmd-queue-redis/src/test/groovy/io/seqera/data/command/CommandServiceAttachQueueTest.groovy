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

import spock.lang.Specification

/**
 * Unit tests for {@link CommandServiceImpl#attachQueue} input validation and
 * collision handling. Kept as a plain Specification with stubbed queues so
 * the guards can be exercised without a running Redis instance.
 */
class CommandServiceAttachQueueTest extends Specification {

    private CommandServiceImpl newService(CommandQueue primary) {
        def svc = new CommandServiceImpl()
        // CommandServiceImpl uses field injection — set the primary queue directly
        // for these unit-level guard tests.
        svc.@queue = primary
        return svc
    }

    private CommandQueue stubQueue(String streamName) {
        return Stub(CommandQueue) {
            it.streamName() >> streamName
        }
    }

    def 'attachQueue rejects null'() {
        given:
        def svc = newService(stubQueue('cmd-queue/v1'))

        when:
        svc.attachQueue(null)

        then:
        thrown(IllegalArgumentException)
    }

    def 'attachQueue rejects a queue whose streamName collides with the primary'() {
        given:
        def svc = newService(stubQueue('cmd-queue/v1'))
        def clash = stubQueue('cmd-queue/v1')

        when:
        svc.attachQueue(clash)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('primary queue')
    }

    def 'attachQueue rejects the same instance twice'() {
        given:
        def svc = newService(stubQueue('cmd-queue/v1'))
        def extra = stubQueue('cmd-monitor/v1')

        when:
        svc.attachQueue(extra)
        svc.attachQueue(extra)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('already attached')
    }

    def 'attachQueue rejects two different queues sharing the same streamName'() {
        given:
        def svc = newService(stubQueue('cmd-queue/v1'))
        def a = stubQueue('cmd-monitor/v1')
        def b = stubQueue('cmd-monitor/v1')

        when:
        svc.attachQueue(a)
        svc.attachQueue(b)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Another queue')
    }

    def 'attachQueue accepts multiple queues with distinct streams'() {
        given:
        def svc = newService(stubQueue('cmd-queue/v1'))
        def monitor = stubQueue('cmd-monitor/v1')
        def cleanup = stubQueue('cmd-cleanup/v1')

        when:
        svc.attachQueue(monitor)
        svc.attachQueue(cleanup)

        then:
        noExceptionThrown()
    }
}
