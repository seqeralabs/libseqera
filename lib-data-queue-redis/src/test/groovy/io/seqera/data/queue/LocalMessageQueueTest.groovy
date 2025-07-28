/*
 * Copyright 2025, Seqera Labs
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

package io.seqera.data.queue

import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.data.queue.impl.LocalMessageQueue
import jakarta.inject.Inject

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class LocalMessageQueueTest extends Specification {

    @Inject
    private LocalMessageQueue queue

    private static String rndHex() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong())
    }

    def 'should return null if empty' () {
        expect:
        queue.poll('foo') == null

        when:
        def start = System.currentTimeMillis()
        and:
        queue.poll('foo', Duration.ofMillis(500)) == null
        and:
        def delta = System.currentTimeMillis()-start
        then:
        assert delta>=500
        assert delta<1000
    }

    def 'should offer and poll a value' () {
        given:
        queue.offer('bar', 'alpha')
        queue.offer('bar', 'beta')

        expect:
        queue.poll('foo') == null
        queue.poll('bar') == 'alpha'
        queue.poll('bar') == 'beta'
    }

    def 'should validate queue length' () {
        given:
        def id1 = "queue-${rndHex()}"
        def id2 = "queue-${rndHex()}"

        expect:
        queue.length('foo') == 0

        when:
        queue.offer(id1, 'one')
        queue.offer(id1, 'two')
        queue.offer(id2, 'three')
        then:
        queue.length(id1) == 2
        queue.length(id2) == 1

        when:
        queue.poll(id1)
        then:
        queue.length(id1) == 1
    }
}
