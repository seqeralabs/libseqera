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

package io.seqera.data.command.store

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.UnaryOperator

import com.github.f4b6a3.tsid.TsidCreator
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import io.seqera.data.command.CommandConfig
import io.seqera.data.command.CommandState
import io.seqera.data.command.CommandStatus
import io.seqera.data.store.state.impl.StateProvider
import io.seqera.serde.jackson.JacksonEncodingStrategy
import jakarta.inject.Inject
import spock.lang.Specification
/**
 * Covers {@code update()}: a command transition is a read-modify-write, and {@code save()} writes
 * the whole record unconditionally, so two writers sharing a stale snapshot silently discard one
 * of the two outcomes.
 *
 * @author Paolo Di Tommaso
 */
@MicronautTest(packages = ['io.seqera.data.workqueue'], transactional = false)
class CommandStateStoreUpdateTest extends Specification {

    @Inject
    CommandStateStore store

    @Inject
    StateProvider<String, String> provider

    /** A store with an explicit update-attempts bound, everything else defaulted. */
    private CommandStateStoreImpl storeWithUpdateAttempts(int attempts) {
        final config = new CommandConfig() {
            @Override
            int stateUpdateAttempts() { attempts }
        }
        return new CommandStateStoreImpl(provider, new JacksonEncodingStrategy<CommandState>() {}, config)
    }

    private CommandState submitted() {
        final state = CommandState.create(TsidCreator.getTsid().toLowerCase(), 'test', null)
        store.save(state)
        return state
    }

    def 'concurrent transitions should not lose a write nor spuriously fail'() {
        given: 'the load-bearing case — fails against a blind save() (lost write) and against a write-mutex (spurious refusal under contention)'
        def state = submitted()
        and: 'two writers that both read the same snapshot before either writes'
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(2)
        def applied = new AtomicInteger()
        and: 'a slow mutator, so the window collides reliably instead of by scheduling luck'
        2.times { i ->
            Thread.start {
                start.await(5, TimeUnit.SECONDS)
                // No caller-side retry: a CAS miss re-reads and re-applies INSIDE update(),
                // so a live command must never see a refusal from mere contention. The 250ms
                // hold would exhaust a lock-wait budget; the CAS just loses one round and wins
                // the next.
                if (store.update(state.id(), { CommandState s ->
                    sleep(250)
                    s.withError("boom-$i")
                } as UnaryOperator)) {
                    applied.incrementAndGet()
                }
                done.countDown()
            }
        }

        when:
        start.countDown()
        done.await(30, TimeUnit.SECONDS)

        then: 'both writers succeeded first-call — contention is absorbed, never surfaced'
        applied.get() == 2
        and: 'both increments survived; a lost write would leave the count at 1'
        store.findById(state.id()).get().errorsCount() == 2
    }

    def 'update should apply the mutator to the stored state and persist it'() {
        given:
        def state = submitted()

        when:
        def applied = store.update(state.id(), { CommandState s -> s.toProcessing() } as UnaryOperator)

        then:
        applied
        store.findById(state.id()).get().startedAt() != null
    }

    def 'update should apply the mutator to the current value, not a stale snapshot'() {
        given: 'a snapshot the caller holds, then a transition by someone else'
        def stale = submitted()
        store.update(stale.id(), { CommandState s -> s.withError('first') } as UnaryOperator)

        when: 'the caller mutates using its stale reference'
        store.update(stale.id(), { CommandState s -> s.withError('second') } as UnaryOperator)

        then: 'the earlier error was not discarded — the streak accumulated'
        def result = store.findById(stale.id()).get()
        result.errorsCount() == 2
        result.error() == 'second'
    }

    def 'update should pin the CAS witness to its own read, whatever the mutator did to the version'() {
        given: 'a live command'
        def state = submitted()

        when: 'a mutator hands back a state whose version is not the one the loop read'
        def applied = store.update(state.id(), { CommandState s -> s.toProcessing().withVersion(0) } as UnaryOperator)

        then: 'the write still lands — an unpinned witness would miss every attempt and refuse silently'
        applied
        store.findById(state.id()).get().startedAt() != null
    }

    def 'terminal result should not overwrite a cancellation'() {
        given:
        def state = submitted()
        store.update(state.id(), { CommandState s -> s.cancelled() } as UnaryOperator)

        when:
        def applied = store.update(state.id(), { CommandState s -> s.completed('done') } as UnaryOperator)

        then:
        !applied
        store.findById(state.id()).get().status() == CommandStatus.CANCELLED
    }

    def 'cancellation should not overwrite a terminal result'() {
        given:
        def state = submitted()
        store.update(state.id(), { CommandState s -> s.completed('done') } as UnaryOperator)

        when:
        def applied = store.update(state.id(), { CommandState s -> s.cancelled() } as UnaryOperator)

        then:
        !applied
        def result = store.findById(state.id()).get()
        result.status() == CommandStatus.SUCCEEDED
        result.result() == 'done'
    }

    def 'update should report false for an unknown command'() {
        expect:
        !store.update('cmd-does-not-exist', { CommandState s -> s.toProcessing() } as UnaryOperator)
    }

    def 'update should give up after the configured update attempts'() {
        given: 'a store bounded to 3 attempts and a mutator that always invalidates its own CAS'
        def bounded = storeWithUpdateAttempts(3)
        def state = CommandState.create(TsidCreator.getTsid().toLowerCase(), 'test', null)
        bounded.save(state)
        def invocations = new AtomicInteger()

        when:
        def applied = bounded.update(state.id(), { CommandState s ->
            invocations.incrementAndGet()
            bounded.save(s.withError('concurrent'))   // out-of-band write: the CAS always misses
            s.toProcessing()
        } as UnaryOperator)

        then: 'the loop ran exactly the configured number of rounds, then reported not applied'
        !applied
        invocations.get() == 3
    }

    def 'a non-positive update attempts bound should fail fast at construction'() {
        when:
        storeWithUpdateAttempts(attempts)

        then:
        thrown(IllegalStateException)

        where:
        attempts << [0, -1]
    }

    def 'update should propagate a mutator failure and remain usable'() {
        given:
        def state = submitted()

        when:
        store.update(state.id(), { CommandState s -> throw new RuntimeException('boom') } as UnaryOperator)

        then:
        thrown(RuntimeException)

        and: 'a following transition proceeds normally — nothing is left behind to expire'
        store.update(state.id(), { CommandState s -> s.toProcessing() } as UnaryOperator)
        store.findById(state.id()).get().startedAt() != null
    }

}
