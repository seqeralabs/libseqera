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
import spock.lang.Specification

import java.util.concurrent.ArrayBlockingQueue

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class AbstractWorkQueueLocalTest extends Specification {

    @Inject
    LocalWorkQueue target

    def 'should offer and consume some messages' () {
        given:
        def id1 = "queue-${LongRndKey.rndHex()}"

        and:
        def queue = new TestQueue(target)
        def sink = new ArrayBlockingQueue(10)
        and:
        queue.addConsumer(id1, { it-> sink.add(it) })

        when:
        queue.offer(id1, new TestMessage('one','two'))
        queue.offer(id1, new TestMessage('alpha','omega'))
        then:
        sink.take()==new TestMessage('one','two')
        sink.take()==new TestMessage('alpha','omega')

        cleanup:
        queue.close()
    }

}
