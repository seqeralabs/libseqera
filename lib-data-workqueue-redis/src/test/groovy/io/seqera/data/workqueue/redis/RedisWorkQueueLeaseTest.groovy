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

package io.seqera.data.workqueue.redis

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import java.util.function.IntSupplier

import io.micronaut.context.ApplicationContext
import io.seqera.data.workqueue.MessageLease
import io.seqera.data.workqueue.metrics.Outcome
import io.seqera.data.workqueue.metrics.QueueMetrics
import io.seqera.fixtures.redis.RedisTestContainer
import io.seqera.random.LongRndKey
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.params.XClaimParams
import redis.clients.jedis.params.XPendingParams
import redis.clients.jedis.params.XReadGroupParams
import spock.lang.Shared
import spock.lang.Specification
import static io.seqera.data.workqueue.MessageConsumer.Decision.ACK
import static io.seqera.data.workqueue.MessageConsumer.Decision.DEFERRED

/**
 * Covers the message-lease (PEL heartbeat) semantics of {@link RedisWorkQueue}:
 * a DEFERRED entry stays leased — invisible to other consumers — for as long as its
 * lease is renewed, redelivers once the heartbeat dies, and renewal is batched so a
 * single tick covers the whole in-flight set with one XPENDING + one XCLAIM.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisWorkQueueLeaseTest extends Specification implements RedisTestContainer {

    static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(2)

    static class LeaseTestConfig implements RedisWorkQueueConfig {
        @Override
        String getDefaultConsumerGroupName() { 'lease-test-group' }
        @Override
        Duration getVisibilityTimeout() { VISIBILITY_TIMEOUT }
        @Override
        Duration getConsumerWarnTimeout() { Duration.ofSeconds(10) }
    }

    /** Counts the lease events the renewal tick reports. */
    static class RecordingMetrics implements QueueMetrics {
        final AtomicInteger lost = new AtomicInteger()
        final AtomicInteger leaks = new AtomicInteger()
        final AtomicInteger errors = new AtomicInteger()
        @Override
        void bindBacklog(String queueId, IntSupplier lengthSupplier) { }
        @Override
        long startSample() { return 0 }
        @Override
        void recordOutcome(long startNanos, String queueId, Outcome outcome) { }
        @Override
        void leaseLost() { lost.incrementAndGet() }
        @Override
        void leaseLeak() { leaks.incrementAndGet() }
        @Override
        void renewError() { errors.incrementAndGet() }
    }

    /**
     * Interleaving hook: runs a callback right after the ownership query returns —
     * the window in which the dispatcher can register a newborn lease concurrently.
     */
    static class RacingQueue extends RedisWorkQueue {
        Runnable onQuery
        @Override
        protected Map<StreamEntryID, String> pendingOwners(Jedis jedis, String queueId, Set<StreamEntryID> ids) {
            final owners = super.pendingOwners(jedis, queueId, ids)
            onQuery?.run()
            return owners
        }
    }

    @Shared
    ApplicationContext context

    List<RedisWorkQueue> queues = []

    def setup() {
        context = ApplicationContext.run('test', 'redis')
    }

    def cleanup() {
        queues.each { (getInternal(it, 'renewalScheduler') as java.util.concurrent.ScheduledExecutorService)?.shutdownNow() }
        queues.clear()
        context.stop()
    }

    private RedisWorkQueue newQueue(QueueMetrics metrics = null) {
        return initQueue(new RedisWorkQueue(), metrics)
    }

    /* Reflection accessors: Groovy's .@ direct field access cannot reach the private
       superclass fields on a RacingQueue instance, so the harness goes through
       java.lang.reflect for both the plain and the subclassed queue. */

    private static void setInternal(RedisWorkQueue queue, String name, Object value) {
        def field = RedisWorkQueue.getDeclaredField(name)
        field.accessible = true
        field.set(queue, value)
    }

    private static Object getInternal(RedisWorkQueue queue, String name) {
        def field = RedisWorkQueue.getDeclaredField(name)
        field.accessible = true
        return field.get(queue)
    }

    private <T extends RedisWorkQueue> T initQueue(T queue, QueueMetrics metrics = null) {
        setInternal(queue, 'pool', context.getBean(JedisPool))
        setInternal(queue, 'config', new LeaseTestConfig())
        setInternal(queue, 'metrics', metrics)
        def create = RedisWorkQueue.getDeclaredMethod('create')
        create.accessible = true
        create.invoke(queue)
        queues << queue
        return queue
    }

    private static boolean noLease(RedisWorkQueue queue, String queueId) {
        final leases = queue.@inFlight.get(queueId)
        return leases == null || leases.isEmpty()
    }

    /** The current PEL owner of the given entry, or null when the entry is not pending. */
    private String pendingOwner(String queueId, StreamEntryID entryId) {
        try (def jedis = context.getBean(JedisPool).getResource()) {
            def pending = jedis.xpending(queueId, 'lease-test-group', new XPendingParams(entryId, entryId, 1))
            return pending ? pending.first().consumerName : null
        }
    }

    /** The idle time in millis of the given pending entry. */
    private long pendingIdle(String queueId, StreamEntryID entryId) {
        try (def jedis = context.getBean(JedisPool).getResource()) {
            def pending = jedis.xpending(queueId, 'lease-test-group', new XPendingParams(entryId, entryId, 1))
            return pending.first().idleTime
        }
    }

    def 'a deferred entry should stay leased past the visibility timeout and settle on ack' () {
        given: 'two competing consumers with a 2s visibility timeout'
        def stream1 = newQueue()
        def stream2 = newQueue()
        def queueId = "stream-${LongRndKey.rndHex()}"
        stream1.init(queueId)
        MessageLease held = null

        when: 'the first consumer defers the settlement to a task'
        stream1.offer(queueId, 'payload')
        def decision = stream1.consume(queueId, { msg, lease -> held = lease; DEFERRED })
        then:
        decision == DEFERRED
        held != null

        when: 'a second consumer keeps polling for 2.5x the visibility timeout'
        def stalled = false
        def deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT.toMillis() * 5 / 2
        while (System.currentTimeMillis() < deadline) {
            if (stream2.consume(queueId, { msg, lease -> ACK }) != null) {
                stalled = true
                break
            }
            sleep 250
        }
        then: 'the heartbeat kept the entry invisible - no second delivery'
        !stalled

        when: 'the task settles from another thread'
        def settler = new Thread({ held.ack() })
        settler.start()
        settler.join()
        then: 'the entry is acked, removed and unregistered'
        stream1.length(queueId) == 0
        noLease(stream1, queueId)
        stream2.consume(queueId, { msg, lease -> assert false /* <-- this should not be invoked */ }) == null
    }

    def 'a dead lease should be redelivered to another consumer after the visibility timeout' () {
        given:
        def stream1 = newQueue()
        def stream2 = newQueue()
        def queueId = "stream-${LongRndKey.rndHex()}"
        stream1.init(queueId)

        when: 'the first consumer defers and then its replica "crashes" - renewal stops, the lease is never settled'
        stream1.offer(queueId, 'payload')
        stream1.consume(queueId, { msg, lease -> DEFERRED })
        stream1.@renewalScheduler.shutdownNow()

        and: 'a second consumer polls past the visibility timeout'
        String redelivered = null
        def deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT.toMillis() * 5
        while (redelivered == null && System.currentTimeMillis() < deadline) {
            stream2.consume(queueId, { msg, lease -> redelivered = msg; ACK })
            sleep 250
        }

        then: 'the entry idles out and is claimed exactly like a dead-consumer failure'
        redelivered == 'payload'
        stream1.length(queueId) == 0
    }

    def 'one renewal tick should cover many in-flight entries in a single batch' () {
        given: 'a stream with the background renewal stopped, so only manual ticks renew'
        def stream = newQueue()
        stream.@renewalScheduler.shutdownNow()
        def queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)
        def count = 40

        and: 'many entries in flight, all deferred'
        count.times { stream.offer(queueId, "msg-$it".toString()) }
        count.times {
            assert stream.consume(queueId, { msg, lease -> DEFERRED }) == DEFERRED
        }

        when: 'idle accumulates, then one manual renewal tick runs'
        sleep 1_000
        def begin = System.nanoTime()
        stream.renewLeases()
        def elapsed = Duration.ofNanos(System.nanoTime() - begin)

        and: 'the pending entries are inspected right after the tick'
        def pending
        try (def jedis = context.getBean(JedisPool).getResource()) {
            pending = jedis.xpending(queueId, 'lease-test-group', new XPendingParams('-', '+', count * 2))
        }

        then: 'the single tick renewed every entry - idle time was reset for all of them'
        pending.size() == count
        pending.every { it.idleTime < 800 }

        and: 'the tick is two round-trips, not one per entry - far below the renewal period'
        elapsed < Duration.ofMillis(VISIBILITY_TIMEOUT.toMillis().intdiv(4))
    }

    def 'retry should release the lease and let the entry redeliver on the claim cadence' () {
        given:
        def stream = newQueue()
        def queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)
        MessageLease held = null

        when:
        stream.offer(queueId, 'payload')
        stream.consume(queueId, { msg, lease -> held = lease; DEFERRED })
        and: 'the task settles as retry - registry removal only, the entry stays pending'
        held.retry()
        then:
        noLease(stream, queueId)
        stream.length(queueId) == 1

        when: 'a late double-settlement is a no-op'
        held.ack()
        then:
        stream.length(queueId) == 1

        when: 'the claim cycle re-delivers after the visibility timeout'
        String redelivered = null
        def deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT.toMillis() * 5
        while (redelivered == null && System.currentTimeMillis() < deadline) {
            stream.consume(queueId, { msg, lease -> redelivered = msg; ACK })
            sleep 250
        }
        then:
        redelivered == 'payload'
        stream.length(queueId) == 0
    }

    def 'retryAfter should hold the lease and redeliver no earlier than the delay' () {
        given: 'two consumers; the first defers and settles with a delayed retry'
        def stream1 = newQueue()
        def stream2 = newQueue()
        def queueId = "stream-${LongRndKey.rndHex()}"
        stream1.init(queueId)
        MessageLease held = null
        def delay = VISIBILITY_TIMEOUT.multipliedBy(5).dividedBy(2)   // 5s for a 2s visibility timeout

        when:
        stream1.offer(queueId, 'payload')
        stream1.consume(queueId, { msg, lease -> held = lease; DEFERRED })
        held.retryAfter(delay)

        and: 'a second consumer polls the whole window'
        long deliveredAt = 0
        def begin = System.currentTimeMillis()
        def deadline = begin + delay.toMillis() * 3
        while (deliveredAt == 0 && System.currentTimeMillis() < deadline) {
            if (stream2.consume(queueId, { msg, lease -> ACK }) != null) {
                deliveredAt = System.currentTimeMillis()
            }
            sleep 200
        }

        then: 'the entry redelivers, but no earlier than the requested delay'
        deliveredAt > 0
        deliveredAt - begin >= delay.toMillis() - 500   // scheduling slop
    }

    def 'a lease held for delayed retry should never be pruned as a leak' () {
        given: 'a consumer whose renewal runs only on manual ticks, with recording metrics'
        def metrics = new RecordingMetrics()
        def stream = newQueue(metrics)
        stream.@renewalScheduler.shutdownNow()
        String queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)
        MessageLease held = null

        when: 'a deferred entry settles with a delay far beyond the age backstop'
        stream.offer(queueId, 'payload')
        stream.consume(queueId, { msg, lease -> held = lease; DEFERRED })
        held.retryAfter(VISIBILITY_TIMEOUT.multipliedBy(30))
        and: 'the lease ages past 3x the visibility timeout, then a renewal tick runs'
        sleep VISIBILITY_TIMEOUT.toMillis() * 3 + 500
        stream.renewLeases()

        then: 'held for release on purpose - not a leak, not lost, still registered and renewed'
        metrics.leaks.get() == 0
        metrics.lost.get() == 0
        stream.@inFlight.get(queueId).size() == 1
    }

    def 'a renewal tick against an exhausted pool should fail fast and loudly, not hang' () {
        given: 'a leased entry, then a bounded-borrow pool fully exhausted by other borrowers'
        def metrics = new RecordingMetrics()
        def stream = newQueue(metrics)
        (getInternal(stream, 'renewalScheduler') as java.util.concurrent.ScheduledExecutorService).shutdownNow()
        String queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)
        stream.offer(queueId, 'payload')
        stream.consume(queueId, { msg, lease -> DEFERRED })
        and: 'every pool connection borrowed away, with a bounded borrow wait (redis.pool.maxWait in prod)'
        def pool = context.getBean(JedisPool)
        pool.setMaxWait(java.time.Duration.ofMillis(500))
        def borrowed = []
        pool.maxTotal.times { borrowed << pool.getResource() }

        when: 'a renewal tick runs while no connection can be borrowed'
        def begin = System.currentTimeMillis()
        stream.renewLeases()
        def elapsed = System.currentTimeMillis() - begin

        then: 'the tick completed within the bound and reported the failure - no silent hang'
        elapsed < 5_000
        metrics.errors.get() >= 1
        and: 'the lease stays registered - the next tick retries the renewal'
        (getInternal(stream, 'inFlight') as Map).get(queueId).size() == 1

        cleanup:
        borrowed.each { it.close() }
        pool.setMaxWait(java.time.Duration.ofMillis(-1))
    }

    def 'the renewal liveness gauge should expose the age of the last completed tick' () {
        given: 'a stream instrumented with real Micrometer metrics, ticking only manually'
        def registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        def stream = initQueue(new RedisWorkQueue(),
                new io.seqera.data.workqueue.metrics.MicrometerQueueMetrics(registry, 'lease-liveness-test'))
        (getInternal(stream, 'renewalScheduler') as java.util.concurrent.ScheduledExecutorService).shutdownNow()

        expect: 'the gauge is registered at stream creation'
        def gauge = registry.find('seqera.workqueue.lease.renewal.age').gauge()
        gauge != null

        when: 'time passes, then a manual tick completes'
        sleep 400
        def before = gauge.value()
        stream.renewLeases()

        then: 'the age was growing and the completed tick reset it - a STUCK tick shows unbounded growth'
        before >= 0.35d
        gauge.value() < before
    }

    def 'a throwing consumer should leave nothing registered' () {
        given:
        def stream = newQueue()
        def queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)

        when:
        stream.offer(queueId, 'payload')
        stream.consume(queueId, { msg, lease -> throw new RuntimeException('Oops') })
        then:
        def e = thrown(RuntimeException)
        e.message == 'Oops'
        and: 'the registration bracket settled the entry as retry'
        noLease(stream, queueId)
        stream.length(queueId) == 1
    }

    def 'a lease taken over by another consumer should be dropped on the next tick and never re-seized' () {
        given: 'a consumer whose renewal runs only on manual ticks, with recording metrics'
        def metrics = new RecordingMetrics()
        def stream = newQueue(metrics)
        stream.@renewalScheduler.shutdownNow()
        String queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)

        when: 'an entry is deferred, then force-claimed by another consumer - as after a renewal outage'
        stream.offer(queueId, 'payload')
        stream.consume(queueId, { msg, lease -> DEFERRED })
        StreamEntryID entryId = stream.@inFlight.get(queueId).keySet().first()
        try (def jedis = context.getBean(JedisPool).getResource()) {
            jedis.xclaim(queueId, 'lease-test-group', 'thief-consumer', 0, new XClaimParams(), entryId)
        }
        and: 'the next renewal tick runs'
        stream.renewLeases()

        then: 'the ownership check dropped the lease and counted it as lost'
        noLease(stream, queueId)
        metrics.lost.get() == 1
        and: 'the thief still owns the entry - no re-seizure ping-pong'
        pendingOwner(queueId, entryId) == 'thief-consumer'
    }

    def 'a lease registered during the ownership check must not be dropped' () {
        given: 'a stream whose ownership query is raced by a concurrent registration'
        def metrics = new RecordingMetrics()
        def stream = initQueue(new RacingQueue(), metrics)
        (getInternal(stream, 'renewalScheduler') as java.util.concurrent.ScheduledExecutorService).shutdownNow()
        String queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)

        and: 'one leased entry, and a second message not yet delivered'
        stream.offer(queueId, 'first')
        assert stream.consume(queueId, { msg, lease -> DEFERRED }) == DEFERRED
        stream.offer(queueId, 'second')

        and: 'the dispatcher races the tick: it claims and registers the second entry right after the ownership query returned'
        stream.onQuery = { stream.consume(queueId, { msg, lease -> DEFERRED }) }

        when:
        stream.renewLeases()

        then: 'the newborn lease survives the tick untouched - it renews on the NEXT tick'
        metrics.lost.get() == 0
        (getInternal(stream, 'inFlight') as Map).get(queueId).size() == 2
    }

    def 'the ownership check should stay exact under a large foreign PEL' () {
        given: 'a consumer whose renewal runs only on manual ticks, with recording metrics'
        def metrics = new RecordingMetrics()
        def stream = newQueue(metrics)
        stream.@renewalScheduler.shutdownNow()
        String queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)

        and: 'a leased entry, then 300 entries pending for ANOTHER consumer of the same group, then a second leased entry'
        stream.offer(queueId, 'low')
        assert stream.consume(queueId, { msg, lease -> DEFERRED }) == DEFERRED
        300.times { stream.offer(queueId, "foreign-$it".toString()) }
        try (def jedis = context.getBean(JedisPool).getResource()) {
            // deliver the 300 to a different consumer: they become foreign PEL entries
            // interleaved between the ids this stream holds leases on
            jedis.xreadGroup('lease-test-group', 'other-consumer',
                    new XReadGroupParams().count(300), Map.of(queueId, StreamEntryID.UNRECEIVED_ENTRY))
        }
        stream.offer(queueId, 'high')
        assert stream.consume(queueId, { msg, lease -> DEFERRED }) == DEFERRED

        and: 'the high leased entry goes stalled and is taken over, as after a renewal outage'
        StreamEntryID highId = (stream.@inFlight.get(queueId).keySet() as List).max()
        try (def jedis = context.getBean(JedisPool).getResource()) {
            jedis.xclaim(queueId, 'lease-test-group', 'thief-consumer', 0, new XClaimParams(), highId)
        }

        when: 'a renewal tick runs with the foreign entries crowding the leased id range'
        stream.renewLeases()

        then: 'the theft was detected despite the crowded range - dropped and counted, never re-seized'
        metrics.lost.get() == 1
        pendingOwner(queueId, highId) == 'thief-consumer'
        stream.@inFlight.get(queueId).size() == 1
    }

    def 'the age backstop should prune a dead-owner lease as a leak but never a live one' () {
        given: 'a consumer whose renewal runs only on manual ticks, with recording metrics'
        def metrics = new RecordingMetrics()
        def stream = newQueue(metrics)
        stream.@renewalScheduler.shutdownNow()
        String queueId = "stream-${LongRndKey.rndHex()}"
        stream.init(queueId)
        def leases = [:]

        and: 'two deferred entries: one bound to a live owner, one whose settlement path never ran'
        stream.offer(queueId, 'alive')
        stream.offer(queueId, 'leaked')
        2.times {
            assert stream.consume(queueId, { msg, lease -> leases[msg] = lease; DEFERRED }) == DEFERRED
        }
        leases['alive'].bindLiveness({ true } as BooleanSupplier)

        when: 'both leases age past 3x the visibility timeout, then a renewal tick runs'
        sleep VISIBILITY_TIMEOUT.toMillis() * 3 + 500
        stream.renewLeases()

        then: 'the dead-owner lease was pruned as a leak; the live one kept its lease'
        metrics.leaks.get() == 1
        stream.@inFlight.get(queueId).values() as List == [leases['alive']]
        and: 'the live lease was actually renewed - its idle clock was just reset'
        pendingIdle(queueId, leases['alive'].@entryId as StreamEntryID) < 800
    }

}
