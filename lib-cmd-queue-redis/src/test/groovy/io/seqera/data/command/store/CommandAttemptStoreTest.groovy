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
 */
package io.seqera.data.command.store

import java.time.Duration

import io.seqera.data.command.CommandState
import io.seqera.data.store.state.impl.LocalStateProvider
import io.seqera.serde.jackson.JacksonEncodingStrategy
import spock.lang.Specification

class CommandAttemptStoreTest extends Specification {

    private CommandStateStoreImpl newStore() {
        new CommandStateStoreImpl(
                new LocalStateProvider(),
                new JacksonEncodingStrategy<CommandState>() {},
                Duration.ofHours(1))
    }

    def 'should create command state idempotently'() {
        given:
        def store = newStore()
        def state = CommandState.submitted('c1', 'test', [value: 1])

        expect:
        store.create(state)
        !store.create(state)
    }

    def 'should fence command attempts by owner and renew their lease'() {
        given:
        def store = newStore()

        expect:
        store.tryAcquireAttempt('c1', 'owner-a', Duration.ofMillis(200))
        !store.tryAcquireAttempt('c1', 'owner-b', Duration.ofMillis(200))
        store.isAttemptOwner('c1', 'owner-a')

        and:
        store.renewAttempt('c1', 'owner-a', Duration.ofSeconds(1))
        !store.renewAttempt('c1', 'owner-b', Duration.ofSeconds(1))
        !store.releaseAttempt('c1', 'owner-b')
        store.releaseAttempt('c1', 'owner-a')
        store.tryAcquireAttempt('c1', 'owner-b', Duration.ofSeconds(1))
    }

    def 'should recover an attempt after its owner lease expires'() {
        given:
        def store = newStore()

        expect:
        store.tryAcquireAttempt('c1', 'dead-owner', Duration.ofMillis(50))

        when:
        sleep 100

        then:
        store.tryAcquireAttempt('c1', 'live-owner', Duration.ofSeconds(1))
    }

    def 'should reject a stale owner state update'() {
        given:
        def store = newStore()
        def submitted = CommandState.submitted('c1', 'test', [value: 1])
        store.create(submitted)
        store.tryAcquireAttempt('c1', 'owner-a', Duration.ofSeconds(1))

        expect:
        !store.saveOwned(submitted.failed('stale'), 'owner-b')
        store.findById('c1').get() == submitted
        store.saveOwned(submitted.submitting(), 'owner-a')
        store.findById('c1').get().status().name() == 'SUBMITTING'
    }
}
