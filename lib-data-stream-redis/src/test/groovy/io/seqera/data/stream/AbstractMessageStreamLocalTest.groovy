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

package io.seqera.data.stream

import io.seqera.random.LongRndKey
import spock.lang.Specification

import java.util.concurrent.ArrayBlockingQueue

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.data.stream.impl.LocalMessageStream
import jakarta.inject.Inject
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest(environments = ['test'])
class AbstractMessageStreamLocalTest extends Specification {

    @Inject
    LocalMessageStream target

    def 'should offer and consume some messages' () {
        given:
        def id1 = "stream-${LongRndKey.rndHex()}"

        and:
        def stream = new TestStream(target)
        def queue = new ArrayBlockingQueue(10)
        and:
        stream.addConsumer(id1, { it-> queue.add(it) })

        when:
        stream.offer(id1, new TestMessage('one','two'))
        stream.offer(id1, new TestMessage('alpha','omega'))
        then:
        queue.take()==new TestMessage('one','two')
        queue.take()==new TestMessage('alpha','omega')
        
        cleanup:
        stream.close()
    }

    def 'should support multiple consumer groups with different TestStreams' () {
        given:
        def streamId = "stream-${LongRndKey.rndHex()}"
        
        // Create multiple TestStreams with different consumer groups  
        def stream1 = new TestStream(target, 'group1')
        def stream2 = new TestStream(target, 'group2')
        def stream3 = new TestStream(target, 'group3')
        
        def queue1 = new ArrayBlockingQueue(10)
        def queue2 = new ArrayBlockingQueue(10)  
        def queue3 = new ArrayBlockingQueue(10)

        and:
        stream1.addConsumer(streamId, { it-> queue1.add(it) })
        stream2.addConsumer(streamId, { it-> queue2.add(it) })
        stream3.addConsumer(streamId, { it-> queue3.add(it) })

        when:
        stream1.offer(streamId, new TestMessage('msg1','data1'))
        stream1.offer(streamId, new TestMessage('msg2','data2'))

        then:
        // All consumer groups should receive the messages
        queue1.take() == new TestMessage('msg1','data1')
        queue1.take() == new TestMessage('msg2','data2')
        
        queue2.take() == new TestMessage('msg1','data1')
        queue2.take() == new TestMessage('msg2','data2')
        
        queue3.take() == new TestMessage('msg1','data1')
        queue3.take() == new TestMessage('msg2','data2')

        cleanup:
        stream1.close()
        stream2.close() 
        stream3.close()
    }

}
