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
import io.micronaut.context.ApplicationContext
import io.seqera.data.command.CommandConfig
import io.seqera.data.command.CommandState
import io.seqera.data.store.state.impl.RedisStateProvider
import io.seqera.data.store.state.impl.StateProvider
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.serde.encode.StringEncodingStrategy
import io.seqera.serde.jackson.JacksonEncodingStrategy
import spock.lang.Shared
import spock.lang.Specification
/**
 * The cross-replica half of the compare-and-swap proof. {@code CommandStateStoreUpdateTest}
 * exercises the same logic against {@code LocalStateProvider}, which is in-JVM — but a single
 * replica's dispatcher is single-threaded, so the realistic competing writer is a <em>second
 * replica</em> reclaiming a message after the claim timeout, and the atomicity it relies on is
 * the versioned {@code replaceIf} Lua script (libseqera#107), which the in-memory provider only
 * approximates. This suite runs the load-bearing scenario through {@code RedisStateProvider}
 * against a real Redis, with two store instances sharing it the way two replicas do — including
 * replicas whose serialized forms differ byte-wise, the cross-pod condition that broke the
 * byte-equality CAS this store used before (sched PR #913 rollout).
 *
 * @author Paolo Di Tommaso
 */
class CommandStateStoreRedisTest extends Specification implements RedisTestContainer {

    @Shared
    ApplicationContext context

    def setup() {
        context = ApplicationContext.run('test', 'redis')
        sleep(500) // workaround to wait for Redis connection, as in RedisStateProviderTest upstream
    }

    def cleanup() {
        context.stop()
    }

    /**
     * Resolved by concrete class, the upstream convention ({@code RedisStateProviderTest}): both
     * providers are active beans under the redis env, and asking for the concrete type either
     * returns the Redis one or fails loudly — no silent fall-back to the in-memory provider,
     * which is the failure mode this suite exists to close.
     */
    private StateProvider<String, String> redisProvider() {
        return context.getBean(RedisStateProvider)
    }

    private CommandStateStoreImpl replicaOn(StateProvider<String, String> provider) {
        return replicaOn(provider, new JacksonEncodingStrategy<CommandState>() {})
    }

    private CommandStateStoreImpl replicaOn(StateProvider<String, String> provider, StringEncodingStrategy<CommandState> encoder) {
        return new CommandStateStoreImpl(provider, encoder, context.getBean(CommandConfig))
    }

    /**
     * Byte-divergent but JSON-equivalent encoding — the single-JVM stand-in for a replica whose
     * JVM serializes the same value with a different property order (Jackson discovers derived
     * accessors via {@code Class.getDeclaredMethods()}, whose order is process-dependent — the
     * sched PR #913 incident). Any CAS comparing re-serialized bytes refuses such a replica's
     * writes forever; the versioned CAS must not care.
     */
    private StringEncodingStrategy<CommandState> divergentEncoder() {
        final inner = new JacksonEncodingStrategy<CommandState>() {}
        return new StringEncodingStrategy<CommandState>() {
            @Override
            String encode(CommandState value) {
                final json = inner.encode(value)
                return '{ ' + json.substring(1)
            }
            @Override
            CommandState decode(String encoded) {
                return inner.decode(encoded)
            }
        }
    }

    def 'concurrent replicas should not lose a write nor spuriously fail'() {
        given: 'two store instances sharing one Redis, as two replicas do'
        def provider = redisProvider()
        def replicaA = replicaOn(provider)
        def replicaB = replicaOn(provider)
        and:
        def state = CommandState.create(TsidCreator.getTsid().toLowerCase(), 'test', null)
        replicaA.save(state)
        and: 'both writers collide inside the read-modify-write window; a CAS miss retries internally'
        def start = new CountDownLatch(1)
        def done = new CountDownLatch(2)
        def applied = new AtomicInteger()
        [replicaA, replicaB].eachWithIndex { store, i ->
            Thread.start {
                start.await(5, TimeUnit.SECONDS)
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

        then: 'both replicas succeeded first-call — contention is absorbed, never surfaced'
        applied.get() == 2
        and: 'both increments survived across instances; a lost write would leave the count at 1'
        replicaA.findById(state.id()).get().errorsCount() == 2
    }

    def 'replicas with byte-divergent serialization should still compare-and-swap'() {
        given: 'two replicas whose encoders produce different bytes for the same value — the cross-pod condition of the sched PR #913 incident'
        def provider = redisProvider()
        def replicaA = replicaOn(provider)
        def replicaB = replicaOn(provider, divergentEncoder())
        and: 'an entry written by replica A'
        def state = CommandState.create(TsidCreator.getTsid().toLowerCase(), 'test', null)
        replicaA.save(state)

        when: 'replica B transitions an entry it did not write, then A transitions B\'s write'
        def appliedB = replicaB.update(state.id(), { CommandState s -> s.withError('from-b') } as UnaryOperator)
        def appliedA = replicaA.update(state.id(), { CommandState s -> s.toProcessing() } as UnaryOperator)

        then: 'both cross-replica writes landed — a byte-comparing CAS would refuse them through every retry'
        appliedB
        appliedA
        and: 'both transitions survived, whoever reads'
        with(replicaA.findById(state.id()).get()) {
            startedAt() != null
            error() == 'from-b'
        }
        with(replicaB.findById(state.id()).get()) {
            startedAt() != null
            error() == 'from-b'
        }
    }

}
