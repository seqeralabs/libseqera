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

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

import io.micronaut.scheduling.TaskExecutors
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Named
/**
 * Test class {@link AbstractMessageQueue} using a {@link io.seqera.util.io.seqera.data.queue.impl.LocalMessageQueue}
 *
 * @author Jordi Deu-Pons <jordi@seqera.io>
 */
@MicronautTest(environments = ['test'])
class AbstractMessageQueueLocalTest extends Specification {

    @Inject
    private MessageQueue<String> broker

    @Named(TaskExecutors.BLOCKING)
    private ExecutorService ioExecutor

    def 'should send and consume a request'() {
        given:
        def queue = new TestMsgQueue(broker, ioExecutor) .start()

        when:
        def result = new CompletableFuture<TestMsg>()
        queue.registerClient('service-key', '123', { result.complete(it) })
        and:
        queue.offer('service-key', new TestMsg('msg-1'))
        then:
        result.get(1, TimeUnit.SECONDS).value == 'msg-1'

        cleanup:
        queue.close()
    }

}
