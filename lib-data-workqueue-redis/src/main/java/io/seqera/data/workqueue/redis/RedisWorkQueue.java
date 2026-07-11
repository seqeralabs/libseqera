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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.data.workqueue.MessageConsumer;
import io.seqera.data.workqueue.WorkQueue;
import io.seqera.random.LongRndKey;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Redis-based implementation of {@link WorkQueue} that provides a distributed, reliable
 * work queue using Redis Streams consumer groups as the underlying storage mechanism.
 *
 * <p>This implementation offers the following features:
 * <ul>
 *   <li><b>Distributed Processing:</b> Supports multiple concurrent consumers across different instances</li>
 *   <li><b>Reliability:</b> Guarantees message delivery consistency across service restarts</li>
 *   <li><b>Consumer Groups:</b> Uses Redis consumer groups for load balancing and fault tolerance</li>
 *   <li><b>Message Claiming:</b> Automatically reclaims stalled messages from failed consumers</li>
 *   <li><b>Persistence:</b> Messages are persisted in Redis until explicitly acknowledged and deleted</li>
 * </ul>
 *
 * <p>The implementation follows Redis Streams best practices:
 * <ul>
 *   <li>Creates consumer groups automatically on initialization</li>
 *   <li>Uses unique consumer names to avoid conflicts</li>
 *   <li>Implements message claiming for handling consumer failures</li>
 *   <li>Acknowledges and removes processed messages to prevent memory bloat</li>
 * </ul>
 *
 * <p>Message processing workflow:
 * <ol>
 *   <li>Attempt to claim any stalled messages from failed consumers</li>
 *   <li>If no stalled messages, read new messages from the queue</li>
 *   <li>Process the message through the provided consumer</li>
 *   <li>Acknowledge and delete the message upon successful processing</li>
 * </ol>
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

    private String consumerName;

    /**
     * Tracks the last claimed message position per queue for round-robin claiming.
     * This ensures fair processing of all pending messages instead of always starting
     * from the beginning of the queue, which would cause message starvation.
     */
    private final Map<String, StreamEntryID> lastClaimCursor = new ConcurrentHashMap<>();

    @PostConstruct
    private void create() {
        consumerName = "consumer-" + LongRndKey.rndLong();
        log.info("Creating Redis work queue - consumer={}", consumerName);
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
     * <p>Reads one entry (a reclaimed stalled one via {@code XAUTOCLAIM}, otherwise a
     * newly delivered one via {@code XREADGROUP >}) <strong>without</strong> acking it.
     * The returned lease id is the Redis {@link StreamEntryID} of the delivered entry.
     */
    @Override
    public Lease<String> receive(String queueId) {
        try (Jedis jedis = pool.getResource()) {
            StreamEntry entry = claimMessage(jedis, queueId);
            if (entry == null) {
                entry = readMessage(jedis, queueId);
            }
            if (entry == null) {
                return null;
            }
            return new Lease<>(entry.getID().toString(), entry.getFields().get(DATA_FIELD));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resets the idle time of the entry to zero by re-claiming it to this same
     * consumer with a {@code min-idle} of {@code 0} using {@code XCLAIM … JUSTID},
     * so an alive consumer keeps ownership of the message regardless of how long the
     * handler runs.
     */
    @Override
    public void renewLease(String queueId, String leaseId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xclaimJustId(
                    queueId,
                    config.getDefaultConsumerGroupName(),
                    consumerName,
                    0L,
                    XClaimParams.xClaimParams(),
                    new StreamEntryID(leaseId));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Acknowledges the entry ({@code XACK}) and permanently removes it from the
     * queue ({@code XDEL}) atomically so it can neither be claimed nor redelivered.
     */
    @Override
    public void ack(String queueId, String leaseId) {
        final var id = new StreamEntryID(leaseId);
        try (Jedis jedis = pool.getResource()) {
            final var tx = jedis.multi();
            // acknowledge the entry has been processed so that it cannot be claimed anymore
            tx.xack(queueId, config.getDefaultConsumerGroupName(), id);
            // this removes permanently the entry from the queue
            tx.xdel(queueId, id);
            tx.exec();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>No-op: the entry remains in the pending-entries list and becomes reclaimable
     * by a peer consumer once its idle time exceeds the visibility timeout.
     */
    @Override
    public void release(String queueId, String leaseId) {
        // no-op: entry stays in the PEL, reclaimable after the visibility timeout
    }

    /**
     * {@inheritDoc}
     *
     * <p>Derived from the configured visibility timeout so an alive consumer's lease
     * is renewed well before a peer could reclaim it.
     */
    @Override
    public Duration heartbeatInterval() {
        return config.getHeartbeatInterval();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Duration maxProcessingTime() {
        return config.getMaxProcessingTime();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean consume(String queueId, MessageConsumer<String> consumer) {
        final long begin = System.currentTimeMillis();
        final Lease<String> lease = receive(queueId);
        if (lease == null) {
            return false;
        }
        if (consumer.accept(lease.message())) {
            ack(queueId, lease.id());
            final var delta = System.currentTimeMillis() - begin;
            if (delta > config.getConsumerWarnTimeoutMillis()) {
                log.warn("Redis work queue - consume processing took {} - offending entry={}; message={}",
                        Duration.ofMillis(delta), lease.id(), lease.message());
            }
            return true;
        }
        else {
            release(queueId, lease.id());
            return false;
        }
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
            log.trace("Redis queue id={}; read entry={}", queueId, entry);
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
            log.trace("Redis queue id={}; claimed entry={}", queueId, entry);
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
}
