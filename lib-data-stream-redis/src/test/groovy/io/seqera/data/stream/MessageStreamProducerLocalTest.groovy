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
class MessageStreamProducerLocalTest extends Specification {

    @Inject
    LocalMessageStream target

    def 'should offer and consume some messages' () {
        given:
        def factory = new MessageStreamProducerFactory(target)
        def producer = factory.createProducer()

        when:
        producer.offer(new TestMessage('one','two'))
        producer.offer(new TestMessage('alpha','omega'))
        then:
        target.length(TestMessage.TOPIC_ID)==2

    }

}
