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

package io.seqera.data.store.future

import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

import io.micronaut.context.annotation.Value
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.serde.moshi.MoshiEncodeStrategy
import jakarta.inject.Inject
import jakarta.inject.Singleton
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@MicronautTest
class AbstractFutureStoreTest extends Specification{

    @Singleton
    static class TestFutureStore extends AbstractFutureStore<String> {

        Duration timeout
        Duration pollInterval

        TestFutureStore(FutureHash queue,
            @Value('${wave.pairing.channel.timeout:1s}') Duration timeout,
            @Value('${wave.pairing.channel.awaitTimeout:100ms}') Duration pollInterval )
        {
            super(queue, new MoshiEncodeStrategy<String>() {})
            this.timeout = timeout
            this.pollInterval = pollInterval
        }

        @Override
        String prefix() {
            return 'foo:'
        }
    }

    @Inject
    TestFutureStore store

    def 'should offer and poll and value' () {

        when:
        def future = store.create('xyz')
        and:
        store.complete('xyz', 'hello')
        then:
        future.get() == 'hello'

    }

    def 'should timeout after one sec' () {
        when:
        def future = store.create('xyz')
        and:
        future.get()
        then:
        def err = thrown(ExecutionException)
        and:
        err.cause.class == TimeoutException
    }

}
