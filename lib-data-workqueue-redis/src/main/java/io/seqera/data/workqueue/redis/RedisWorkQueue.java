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

package io.seqera.data.workqueue.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.data.workqueue.MessageConsumer;
import io.seqera.data.workqueue.MessageLease;
import io.seqera.data.workqueue.WorkQueue;
import io.seqera.data.workqueue.metrics.NoopQueueMetrics;
import io.seqera.data.workqueue.metrics.QueueMetrics;
import io.seqera.random.LongRndKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XClaimParams;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamPendingEntry;

/**
 * Redis-based implementation of {@link WorkQueue} that provides a distributed, reliable
 * work queue using Redis Streams as the underlying storage mechanism.
 *
 * <p>This implementation offers the following features:
 * <ul>
 *   <li><b>Distributed Processing:</b> Supports multiple concurrent consumers across different instances</li>
 *   <li><b>Reliability:</b> Guarantees message delivery consistency across service restarts</li>
 *   <li><b>Consumer Groups:</b> Uses Redis consumer groups for load balancing and fault tolerance</li>
 *   <li><b>Message Claiming:</b> Automatically reclaims stalled messages from failed consumers</li>
 *   <li><b>Message Leases:</b> Entries handed to a consumer stay leased — a background
 *       heartbeat renews their idle clock so a live handler can run past the visibility
 *       timeout without the entry going stalled; the visibility timeout degrades to its
 *       one legitimate purpose, detecting a dead consumer</li>
 *   <li><b>Persistence:</b> Messages are persisted in Redis until explicitly acknowledged and deleted</li>
 * </ul>
 *
 * <p>Message processing workflow:
 * <ol>
 *   <li>Attempt to claim any stalled messages from failed consumers</li>
 *   <li>If no stalled messages, read new messages from the queue</li>
 *   <li>Register the entry in the in-flight lease registry</li>
 *   <li>Process the message through the provided consumer</li>
 *   <li>Settle according to the consumer's {@link MessageConsumer.Decision}: {@code ACK}
 *       acknowledges and deletes the entry, {@code RETRY} releases the lease so the entry
 *       redelivers after the visibility timeout, {@code DEFERRED} leaves the entry leased
 *       until the consumer's task settles it via {@link MessageLease}</li>
 * </ol>
 *
 * <p>Lease renewal: a single daemon scheduler renews all in-flight entries per queue in
 * one round-trip at {@code visibility timeout / 4}. Renewal performs an ownership check
 * first (an entry taken over by another consumer during a renewal outage is dropped, never
 * re-seized) and applies a liveness-gated age backstop (a lease older than the max lease
 * age whose owner is not provably alive — see {@link MessageLease#bindLiveness} — is a
 * registry leak: renewal stops so the claim cycle can recover the entry; a lease whose
 * bound owner is still running is never age-pruned).
 *
 * <p>This class is automatically activated when the 'redis' environment is active
 * and requires a configured {@link JedisPool} for Redis connectivity.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
@Requires(bean = RedisActivator.class)
@Singleton
public class RedisWorkQueue implements WorkQueue<String> {

    private static final Logger log = LoggerFactory.getLogger(RedisWorkQueue.class);

    private static final StreamEntryID STREAM_ENTRY_ZERO = new StreamEntryID("0-0");

    private static final String DATA_FIELD = "data";

    @Inject
    private JedisPool pool;

    @Inject
    private RedisWorkQueueConfig config;

    @Inject
    @Nullable
    private QueueMetrics metrics;

    private String consumerName;

    /**
     * Tracks the last claimed message position per queue for round-robin claiming.
     * This ensures fair processing of all pending messages instead of always starting
     * from the beginning of the queue, which would cause message starvation.
     */
    private final Map<String, StreamEntryID> lastClaimCursor = new ConcurrentHashMap<>();

    /**
     * Leased entries per queue: registered before the consumer runs, removed on settle.
     * Queue-scoped (composite) keying — a bare {@link StreamEntryID} key would collide
     * across queues, since entry IDs are unique only per Redis stream key.
     */
    private final Map<String, Map<StreamEntryID, Lease>> inFlight = new ConcurrentHashMap<>();

    private ScheduledExecutorService renewalScheduler;

    /**
     * Completion timestamp (nanos) of the last renewal tick — the liveness signal behind
     * the {@code lease.renewal.age} gauge. A renewal thread blocked before the tick's
     * finally (e.g. on an unbounded pool borrow) is otherwise INVISIBLE: no exception, no
     * renewError, no overrun warn — while every lease on the replica quietly ages toward
     * the visibility timeout. Initialized at creation so the gauge measures scheduler
     * silence from startup, not from the first successful tick.
     */
    private volatile long lastTickCompletedNanos;

    private long renewPeriodNanos;

    private long maxLeaseAgeNanos;

    @PostConstruct
    private void create() {
        consumerName = "consumer-" + LongRndKey.rndLong();
        if (metrics == null) {
            metrics = NoopQueueMetrics.INSTANCE;
        }
        // Fail fast on tuning that cannot protect anything — never silently convert an
        // invalid value into unsafe behavior (a clamped 1ms period would mean ~1000
        // renewal pipelines per second; a non-positive max age would prune every unbound
        // lease on its first tick). Warn when the period eats the missed-tick tolerance
        // (default visibility-timeout/4 tolerates two missed ticks).
        final long renewPeriodMillis = config.getLeaseRenewalPeriodMillis();
        if (renewPeriodMillis <= 0) {
            throw new IllegalStateException("Lease renewal period must be positive - offending value: " + renewPeriodMillis + "ms");
        }
        if (renewPeriodMillis >= config.getVisibilityTimeoutMillis()) {
            throw new IllegalStateException("Lease renewal period (" + renewPeriodMillis
                    + "ms) must be below the visibility timeout (" + config.getVisibilityTimeoutMillis() + "ms)");
        }
        if (renewPeriodMillis > config.getVisibilityTimeoutMillis() / 3) {
            log.warn("Lease renewal period {}ms leaves no missed-tick tolerance before the visibility timeout {}ms - a single failed renewal may lose leases",
                    renewPeriodMillis, config.getVisibilityTimeoutMillis());
        }
        final long maxLeaseAgeMillis = config.getMaxLeaseAgeMillis();
        if (maxLeaseAgeMillis <= 0) {
            throw new IllegalStateException("Max lease age must be positive - offending value: " + maxLeaseAgeMillis + "ms");
        }
        if (maxLeaseAgeMillis < config.getVisibilityTimeoutMillis()) {
            log.warn("Max lease age {}ms is below the visibility timeout {}ms - the leak backstop may prune unbound leases before their first claim window elapses",
                    maxLeaseAgeMillis, config.getVisibilityTimeoutMillis());
        }
        renewPeriodNanos = TimeUnit.MILLISECONDS.toNanos(renewPeriodMillis);
        maxLeaseAgeNanos = TimeUnit.MILLISECONDS.toNanos(maxLeaseAgeMillis);
        renewalScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "redis-workqueue-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
        lastTickCompletedNanos = System.nanoTime();
        metrics.bindRenewalLiveness(this::renewalAgeNanos);
        renewalScheduler.scheduleAtFixedRate(this::renewLeases, renewPeriodMillis, renewPeriodMillis, TimeUnit.MILLISECONDS);
        log.info("Creating Redis work queue - consumer={}; lease renewal period={}ms; max lease age={}ms",
                consumerName, renewPeriodMillis, config.getMaxLeaseAgeMillis());
    }

    @PreDestroy
    private void destroy() {
        if (renewalScheduler != null) {
            renewalScheduler.shutdownNow();
        }
    }

    protected boolean initGroup0(Jedis jedis, String queueId, String group) {
        log.debug("Initializing Redis group='{}'; queueId='{}'", group, queueId);
        try {
            jedis.xgroupCreate(queueId, group, STREAM_ENTRY_ZERO, true);
            return true;
        }
        catch (JedisDataException e) {
            if (e.getMessage().contains("BUSYGROUP")) {
                // The group already exists, so we can safely ignore this exception
                log.info("Redis work queue - consume group={} already exists", group);
                return true;
            }
            throw e;
        }
    }

    @Override
    public void init(String queueId) {
        log.info("Initializing Redis work queue={}; consumer={}; config={}", queueId, consumerName, config);
        try (Jedis jedis = pool.getResource()) {
            initGroup0(jedis, queueId, config.getDefaultConsumerGroupName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void offer(String queueId, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(queueId, StreamEntryID.NEW_ENTRY, Map.of(DATA_FIELD, message));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Registration bracket: the entry is registered in the lease registry <em>before</em>
     * the consumer is invoked and un-registered on every path except a <em>returned</em>
     * {@link MessageConsumer.Decision#DEFERRED} — including a thrown exception, which
     * settles as {@code RETRY} and propagates to the caller. Only the {@code DEFERRED}
     * return value transfers the lease to the consumer's task.
     */
    @Override
    public MessageConsumer.Decision consume(String queueId, MessageConsumer<String> consumer) {
        try (Jedis jedis = pool.getResource()) {
            final long begin = System.currentTimeMillis();
            StreamEntry entry = claimMessage(jedis, queueId);
            if (entry == null) {
                entry = readMessage(jedis, queueId);
            }
            if (entry == null) {
                return null;
            }
            final String msg = entry.getFields().get(DATA_FIELD);
            final Lease lease = register(queueId, entry.getID());
            final MessageConsumer.Decision decision = acceptMessage(consumer, msg, lease, queueId);
            settle(jedis, lease, decision, begin, msg);
            return decision;
        }
    }

    /**
     * Invoke the consumer under the registration bracket: a thrown exception (or a null
     * decision) un-registers the entry — settling it as RETRY, redelivered after the
     * visibility timeout — and propagates to the caller.
     */
    private MessageConsumer.Decision acceptMessage(MessageConsumer<String> consumer, String msg, Lease lease, String queueId) {
        try {
            return Objects.requireNonNull(consumer.accept(msg, lease), "Message consumer returned a null decision");
        }
        catch (Throwable e) {
            lease.settleAsRetry();
            log.error("Redis work queue - consumer errored for queue={}; entry={} - the entry will be redelivered after the visibility timeout", queueId, lease.entryId, e);
            throw e;
        }
    }

    /**
     * Settle a synchronous decision. {@code DEFERRED} leaves the entry registered — the
     * consumer's task owns the lease and settles it via {@link MessageLease}. For
     * {@code ACK} and {@code RETRY} the first settlement wins: when the consumer already
     * settled through the lease, the returned decision is a no-op.
     */
    private void settle(Jedis jedis, Lease lease, MessageConsumer.Decision decision, long begin, String msg) {
        if (decision == MessageConsumer.Decision.DEFERRED) {
            return;
        }
        if (!lease.trySettle()) {
            return;
        }
        unregister(lease);
        if (decision == MessageConsumer.Decision.ACK) {
            final long delta = System.currentTimeMillis() - begin;
            if (delta > config.getConsumerWarnTimeoutMillis()) {
                log.warn("Redis work queue - consume processing took {} - offending entry={}; message={}",
                        Duration.ofMillis(delta), lease.entryId, msg);
            }
            ackAndDelete(jedis, lease.queueId, lease.entryId);
        }
        // RETRY: stopping renewal is the release — the entry stays pending and the
        // claim cadence is the retry schedule; no Redis call at all
    }

    /**
     * Acknowledge the entry and permanently remove it from the queue, atomically.
     */
    private void ackAndDelete(Jedis jedis, String queueId, StreamEntryID entryId) {
        final var tx = jedis.multi();
        tx.xack(queueId, config.getDefaultConsumerGroupName(), entryId);
        tx.xdel(queueId, entryId);
        tx.exec();
    }

    private Lease register(String queueId, StreamEntryID entryId) {
        final Lease lease = new Lease(queueId, entryId);
        inFlight.computeIfAbsent(queueId, k -> new ConcurrentHashMap<>()).put(entryId, lease);
        return lease;
    }

    /**
     * Remove a lease from the in-flight registry. Removes by (key, value) so a stale
     * lease for a re-claimed entry can never evict the registration of its successor.
     */
    private void unregister(Lease lease) {
        final Map<StreamEntryID, Lease> leases = inFlight.get(lease.queueId);
        if (leases != null) {
            leases.remove(lease.entryId, lease);
        }
    }

    private long renewalAgeNanos() {
        return System.nanoTime() - lastTickCompletedNanos;
    }

    private int leasedCount() {
        int count = 0;
        for (Map<StreamEntryID, Lease> leases : inFlight.values()) {
            count += leases.size();
        }
        return count;
    }

    /**
     * Age of the oldest currently-leased entry, in nanoseconds; zero when nothing is
     * leased. Backs the max-lease-age gauge sampled on every renewal tick.
     */
    private long oldestLeaseAgeNanos() {
        long max = 0;
        for (Map<StreamEntryID, Lease> leases : inFlight.values()) {
            for (Lease lease : leases.values()) {
                max = Math.max(max, lease.ageNanos());
            }
        }
        return max;
    }

    /**
     * Renew all in-flight leases. Scheduled at {@code fixedRate = visibilityTimeout / 4}.
     * The tick body catches {@link Throwable} — an escaping {@link Error} would silently
     * cancel a fixedRate task forever, losing every lease on the replica at once.
     */
    void renewLeases() {
        final long start = System.nanoTime();
        try {
            inFlight.forEach(this::renewQueue);
        }
        catch (Throwable t) {
            log.error("Lease renewal tick failed", t);           // outer wall; renewQueue contains per queue
        }
        finally {
            final long elapsed = System.nanoTime() - start;
            lastTickCompletedNanos = System.nanoTime();
            metrics.renewTick(elapsed, leasedCount(), oldestLeaseAgeNanos());
            if (elapsed > renewPeriodNanos) {
                log.warn("Lease renewal tick took {} - exceeds the renewal period; leases at risk", Duration.ofNanos(elapsed));
            }
        }
    }

    private void renewQueue(String queueId, Map<StreamEntryID, Lease> leases) {
        if (leases.isEmpty()) {
            return;
        }
        /* One immutable snapshot drives the WHOLE tick — query, backstop, prune and renew.
           Iterating the live map after the ownership query would race the dispatcher: a
           lease registered while the pipeline was in flight was never queried, and treating
           its missing owner as "no longer pending" would drop a newborn lease (and its
           heartbeat) — resurfacing the take-over-mid-flight bug. Entries registered after
           the snapshot are renewed on the NEXT tick, which is safe by construction: a
           newborn was just claimed or read (idle clock ≈ 0) and the renewal period is
           validated to be well below the visibility timeout. */
        final List<Map.Entry<StreamEntryID, Lease>> snapshot = List.copyOf(leases.entrySet());
        try (Jedis jedis = pool.getResource()) {
            /* 1. Ownership check, one XPENDING range call: an entry that went stalled during
                  a renewal outage now belongs to another consumer. Renewing it blindly
                  (minIdle=0 seizes regardless of owner) would take it back mid-execution —
                  ownership ping-pong. Instead: drop it, count it, log it. This makes the
                  residual duplicate window OBSERVABLE instead of silent.
               2. Age backstop, liveness-gated: a lease older than the max lease age whose
                  owner is not provably alive is a registry leak (a settlement path that
                  never ran) — stop renewing, log loudly, let the claim cycle recover the
                  entry. A leak can never be permanent, and a live handler is never
                  age-pruned, however long it runs.
               3. One variadic XCLAIM JUSTID for everything still ours — a single round-trip
                  per queue per tick, so tick duration does not scale with in-flight count
                  (25 sequential renewals against a slow-but-alive Redis would exceed the
                  visibility timeout and lose every lease exactly when Redis degrades). */
            final List<StreamEntryID> mine = checkOwnershipAndPrune(jedis, queueId, leases, snapshot);   // XPENDING
            if (!mine.isEmpty()) {
                jedis.xclaimJustId(queueId, config.getDefaultConsumerGroupName(), consumerName,
                        0, new XClaimParams(), mine.toArray(StreamEntryID[]::new));
            }
        }
        catch (Exception e) {
            // Transient: the next tick retries — two consecutive failed ticks are tolerated
            // before a lease is at risk (period = visibility timeout / 4).
            metrics.renewError();
            log.warn("Lease renewal errored for queue {}, will retry", queueId, e);
        }
    }

    /**
     * Return the leased entry ids still owned by this consumer, pruning from the registry
     * the leases lost to another consumer, the leases whose entry is no longer pending at
     * all, and those older than the age backstop whose owner is not provably alive.
     *
     * <p>Two invariants: <b>an id is renewed only when its ownership was positively
     * confirmed this tick</b> — unknown must never degrade to "renew", because the
     * renewal XCLAIM (minIdle=0) seizes regardless of the current owner — and <b>only
     * the ids in the snapshot are ever judged</b>: a lease registered concurrently was
     * never queried, so judging it against this tick's answers would misread it as
     * "no longer pending" and drop it. Removals go through the live map conditionally
     * ({@code remove(key, value)}), so a settled-and-re-registered successor is never
     * evicted either.
     */
    private List<StreamEntryID> checkOwnershipAndPrune(Jedis jedis, String queueId,
            Map<StreamEntryID, Lease> leases, List<Map.Entry<StreamEntryID, Lease>> snapshot) {
        final Set<StreamEntryID> ids = new LinkedHashSet<>(snapshot.size());
        for (Map.Entry<StreamEntryID, Lease> entry : snapshot) {
            ids.add(entry.getKey());
        }
        final Map<StreamEntryID, String> owners = pendingOwners(jedis, queueId, ids);
        final List<StreamEntryID> mine = new ArrayList<>(snapshot.size());
        for (Map.Entry<StreamEntryID, Lease> entry : snapshot) {
            final StreamEntryID id = entry.getKey();
            final Lease lease = entry.getValue();
            if (lease.isReleaseDue()) {
                // A delayed retry reached its deadline: release — stop renewing, let the
                // idle clock run; the entry redelivers one visibility timeout later.
                leases.remove(id, lease);
                continue;
            }
            if (!lease.isHeldForRelease() && lease.ageNanos() > maxLeaseAgeNanos && !lease.isOwnerAlive()) {
                leases.remove(id, lease);
                metrics.leaseLeak();
                log.warn("Redis work queue - LEASE LEAK: queue={}; entry={}; age={} - a settlement path never ran and no live owner is bound; renewal stops so the claim cycle recovers the entry", queueId, id, Duration.ofNanos(lease.ageNanos()));
                continue;
            }
            final String owner = owners.get(id);
            if (owner == null) {
                // Exact per-id answer: the entry is not pending at all — acked or deleted
                // outside this lease. A settled lease racing its own un-registration is
                // benign; an UNSETTLED one means someone else finished the entry (a
                // consumer that took it over and completed, or an out-of-band ack) —
                // count it as lost.
                leases.remove(id, lease);
                if (!lease.isSettled()) {
                    metrics.leaseLost();
                    log.warn("Redis work queue - lease lost: queue={}; entry={} is no longer pending under an active lease - a duplicate execution is possible", queueId, id);
                }
                continue;
            }
            if (!owner.equals(consumerName)) {
                leases.remove(id, lease);
                metrics.leaseLost();
                log.warn("Redis work queue - lease lost: queue={}; entry={} is now owned by consumer={} - dropping it to avoid ownership ping-pong; a duplicate execution is possible", queueId, id, owner);
                continue;
            }
            mine.add(id);
        }
        return mine;
    }

    /**
     * Map each leased entry to its current PEL owner: one pipelined per-id XPENDING —
     * a single round-trip with exact answers, bounded by the leased count.
     *
     * <p>A range query capped by a count is NOT safe here: leased ids bracketing more
     * foreign pending entries than the cap get truncated out of the response, and
     * treating "missing" as "safe to renew" would re-seize an entry another consumer
     * legitimately owns — a silent duplicate execution. With per-id queries an absent
     * id has exactly one meaning: the entry is no longer pending.
     */
    protected Map<StreamEntryID, String> pendingOwners(Jedis jedis, String queueId, Set<StreamEntryID> ids) {
        final String group = config.getDefaultConsumerGroupName();
        final Pipeline pipeline = jedis.pipelined();
        final Map<StreamEntryID, Response<List<StreamPendingEntry>>> queries = new HashMap<>();
        for (StreamEntryID id : ids) {
            queries.put(id, pipeline.xpending(queueId, group, new XPendingParams(id, id, 1)));
        }
        pipeline.sync();
        final Map<StreamEntryID, String> owners = new HashMap<>();
        for (Map.Entry<StreamEntryID, Response<List<StreamPendingEntry>>> query : queries.entrySet()) {
            final List<StreamPendingEntry> pending = query.getValue().get();
            if (pending != null && !pending.isEmpty()) {
                owners.put(query.getKey(), pending.get(0).getConsumerName());
            }
        }
        return owners;
    }

    protected StreamEntry readMessage(Jedis jedis, String queueId) {
        // Create parameters for reading with a group
        final var params = new XReadGroupParams()
                // Read one message at a time
                .count(1);

        // Read new messages from the queue using the correct xreadGroup signature
        List<Map.Entry<String, List<StreamEntry>>> messages = jedis.xreadGroup(
                config.getDefaultConsumerGroupName(),
                consumerName,
                params,
                Map.of(queueId, StreamEntryID.UNRECEIVED_ENTRY));

        StreamEntry entry = null;
        if (messages != null && !messages.isEmpty()) {
            List<StreamEntry> entries = messages.get(0).getValue();
            if (entries != null && !entries.isEmpty()) {
                entry = entries.get(0);
            }
        }
        if (entry != null) {
            log.trace("Redis work queue id={}; read entry={}", queueId, entry);
        }
        return entry;
    }

    protected StreamEntry claimMessage(Jedis jedis, String queueId) {
        // Attempt to claim any pending messages that are idle for more than the threshold
        final var params = new XAutoClaimParams()
                // claim one entry at time
                .count(1);

        /* Use the last claim cursor position for round-robin claiming.

        Without this, xautoclaim always starts from "0-0", causing message starvation:
        - Messages 1-10 become claimable in staggered sequence (each ~60s after processing)
        - Scanning from "0-0" always finds the first claimable one among 1-10
        - There's always at least one of 1-10 claimable, so messages 11+ are never reached

        Example starvation pattern:
          Poll at T=60s: xautoclaim(start="0-0") → msg-1 idle=60s ✓ MATCH → claim msg-1
          Poll at T=61s: xautoclaim(start="0-0") → msg-1 idle=1s ✗, msg-2 idle=61s ✓ MATCH
          Poll at T=62s: xautoclaim(start="0-0") → msg-1 ✗, msg-2 ✗, msg-3 idle=62s ✓ MATCH
          ... messages 1-10 rotate, messages 11+ never claimed

        The fix: continue from where we left off (cursor advances even when claiming):
          Poll 1: start=0-0    → claim msg-1  → cursor=msg-2
          Poll 2: start=msg-2  → claim msg-2  → cursor=msg-3
          ...
          Poll 11: start=msg-11 → claim msg-11 → finally reached! */
        final var startId = lastClaimCursor.getOrDefault(queueId, STREAM_ENTRY_ZERO);

        Map.Entry<StreamEntryID, List<StreamEntry>> messages;
        try {
            messages = jedis.xautoclaim(
                    queueId,
                    config.getDefaultConsumerGroupName(),
                    consumerName,
                    config.getVisibilityTimeoutMillis(),
                    startId,
                    params
            );
        } catch (JedisDataException e) {
            if (e.getMessage().contains("NOGROUP")) {
                // The group does not exist. We initialize it and avoid printing the exception
                log.info("Redis work queue - consume group={} do not exist", queueId);
                init(queueId);
            }
            throw e;
        }
        if (messages != null) {
            updateClaimCursor(queueId, messages.getKey());
        }

        final var entry = (messages != null && messages.getValue() != null && !messages.getValue().isEmpty())
                ? messages.getValue().get(0)
                : null;
        if (entry != null) {
            log.trace("Redis work queue id={}; claimed entry={}", queueId, entry);
        }
        return entry;
    }

    /* Update the claim cursor for the next iteration. When xautoclaim reaches
       the end of the PEL, it returns "0-0" signaling wrap around to the beginning. */
    protected void updateClaimCursor(String queueId, StreamEntryID nextCursor) {
        if (nextCursor == null)
            return;
        if (STREAM_ENTRY_ZERO.equals(nextCursor))
            lastClaimCursor.remove(queueId);
        else
            lastClaimCursor.put(queueId, nextCursor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int length(String queueId) {
        try (Jedis jedis = pool.getResource()) {
            return (int) jedis.xlen(queueId);
        }
    }

    /**
     * Settlement handle for one in-flight entry. Idempotent — the first {@code ack()} or
     * {@code retry()} wins — and callable from any thread: {@code ack()} checks out its
     * own pooled connection.
     */
    private final class Lease implements MessageLease {

        private final String queueId;

        private final StreamEntryID entryId;

        /** Max-age backstop clock: the moment the entry was registered. */
        private final long registeredAt = System.nanoTime();

        /** First settlement wins; every later call on either method is a no-op. */
        private final AtomicBoolean settled = new AtomicBoolean();

        /** Owner-liveness probe bound by the lease taker; null when never bound. */
        private volatile BooleanSupplier liveness;

        /**
         * Deadline (nanos) at which a delayed-retry lease is released to the normal
         * redelivery clock; zero when the lease is not held for delayed retry.
         */
        private volatile long releaseAtNanos;

        private Lease(String queueId, StreamEntryID entryId) {
            this.queueId = queueId;
            this.entryId = entryId;
        }

        private long ageNanos() {
            return System.nanoTime() - registeredAt;
        }

        /** True only when a probe is bound and reports the owning task still running. */
        private boolean isOwnerAlive() {
            final BooleanSupplier probe = liveness;
            return probe != null && probe.getAsBoolean();
        }

        private boolean trySettle() {
            return settled.compareAndSet(false, true);
        }

        private boolean isSettled() {
            return settled.get();
        }

        private void settleAsRetry() {
            if (trySettle()) {
                unregister(this);
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>Un-register first, Redis second: a failed XACK then degrades to the benign
         * case — the idle clock resumes, the entry redelivers, and the redelivery acks on
         * the caller's terminal check — instead of a heartbeated-forever orphan.
         */
        @Override
        public void ack() {
            if (!trySettle()) {
                return;
            }
            unregister(this);
            try (Jedis jedis = pool.getResource()) {
                ackAndDelete(jedis, queueId, entryId);
            }
            catch (Exception e) {
                log.warn("Redis work queue - ack failed for queue={}; entry={} - the entry will be redelivered and acked on the terminal check", queueId, entryId, e);
            }
        }

        /**
         * {@inheritDoc}
         *
         * <p>Registry removal only: stopping renewal is the release, and the claim
         * cadence is the retry schedule — no Redis call at all.
         */
        @Override
        public void retry() {
            settleAsRetry();
        }

        /**
         * {@inheritDoc}
         *
         * <p>The natural idle-out already takes one visibility timeout after release, so
         * only the excess is held: the lease stays REGISTERED — renewed, never stalled —
         * until {@code delay - visibility timeout} elapses, then the renewal tick releases
         * it and the idle clock delivers at ≈ the requested delay. A held lease is
         * settled (late {@code ack()}/{@code retry()} are no-ops) and is deliberate:
         * the age backstop never prunes it as a leak.
         */
        @Override
        public void retryAfter(Duration delay) {
            final long holdNanos = delay.toNanos() - TimeUnit.MILLISECONDS.toNanos(config.getVisibilityTimeoutMillis());
            if (holdNanos <= 0) {
                retry();
                return;
            }
            if (settled.compareAndSet(false, true)) {
                // A renewal tick racing this assignment sees a settled, registered,
                // not-yet-held lease for at most one tick: it just renews it — benign.
                releaseAtNanos = System.nanoTime() + holdNanos;
            }
        }

        private boolean isHeldForRelease() {
            return releaseAtNanos > 0;
        }

        private boolean isReleaseDue() {
            return releaseAtNanos > 0 && System.nanoTime() >= releaseAtNanos;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Gates the age backstop: while the probe reports the owning task alive, the
         * lease is never pruned as a leak — a slow handler keeps its lease for as long
         * as it actually runs.
         */
        @Override
        public void bindLiveness(BooleanSupplier alive) {
            this.liveness = alive;
        }
    }
}
