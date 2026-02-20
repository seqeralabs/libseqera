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

package io.seqera.data.stream.impl;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.data.stream.MessageConsumer;
import io.seqera.data.stream.MessageStream;
import io.seqera.random.LongRndKey;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Redis-based implementation of {@link MessageStream} that provides distributed message
 * streaming capabilities using Redis Streams as the underlying storage mechanism.
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
 *   <li>If no stalled messages, read new messages from the stream</li>
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
public class RedisMessageStream implements MessageStream<String> {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageStream.class);

    private static final StreamEntryID STREAM_ENTRY_ZERO = new StreamEntryID("0-0");

    private static final String DATA_FIELD = "data";

    @Inject
    private JedisPool pool;

    @Inject
    private RedisStreamConfig config;

    private String consumerName;

    /**
     * Tracks the last claimed message position per stream for round-robin claiming.
     * This ensures fair processing of all pending messages instead of always starting
     * from the beginning of the stream, which would cause message starvation.
     */
    private final Map<String, StreamEntryID> lastClaimCursor = new ConcurrentHashMap<>();

    @PostConstruct
    private void create() {
        consumerName = "consumer-" + LongRndKey.rndLong();
        log.info("Creating Redis message stream - consumer={}", consumerName);
    }

    protected boolean initGroup0(Jedis jedis, String streamId, String group) {
        log.debug("Initializing Redis group='{}'; streamId='{}'", group, streamId);
        try {
            jedis.xgroupCreate(streamId, group, STREAM_ENTRY_ZERO, true);
            return true;
        }
        catch (JedisDataException e) {
            if (e.getMessage().contains("BUSYGROUP")) {
                // The group already exists, so we can safely ignore this exception
                log.info("Redis message stream - consume group={} already exists", group);
                return true;
            }
            throw e;
        }
    }

    @Override
    public void init(String streamId) {
        log.info("Initializing Redis message stream={}; consumer={}; config={}", streamId, consumerName, config);
        try (Jedis jedis = pool.getResource()) {
            initGroup0(jedis, streamId, config.getDefaultConsumerGroupName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void offer(String streamId, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(streamId, StreamEntryID.NEW_ENTRY, Map.of(DATA_FIELD, message));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean consume(String streamId, MessageConsumer<String> consumer) {
        try (Jedis jedis = pool.getResource()) {
            String msg;
            long begin = System.currentTimeMillis();
            StreamEntry entry = claimMessage(jedis, streamId);
            if (entry == null) {
                entry = readMessage(jedis, streamId);
            }
            if (entry != null && consumer.accept(msg = entry.getFields().get(DATA_FIELD))) {
                Transaction tx = jedis.multi();
                // acknowledge the entry has been processed so that it cannot be claimed anymore
                tx.xack(streamId, config.getDefaultConsumerGroupName(), entry.getID());
                long delta = System.currentTimeMillis() - begin;
                if (delta > config.getConsumerWarnTimeoutMillis()) {
                    log.warn("Redis message stream - consume processing took {} - offending entry={}; message={}",
                            Duration.ofMillis(delta), entry.getID(), msg);
                }
                // this remove permanently the entry from the stream
                tx.xdel(streamId, entry.getID());
                tx.exec();
                return true;
            }
            else {
                return false;
            }
        }
    }

    protected StreamEntry readMessage(Jedis jedis, String streamId) {
        // Create parameters for reading with a group
        XReadGroupParams params = new XReadGroupParams()
                // Read one message at a time
                .count(1);

        // Read new messages from the stream using the correct xreadGroup signature
        List<Map.Entry<String, List<StreamEntry>>> messages = jedis.xreadGroup(
                config.getDefaultConsumerGroupName(),
                consumerName,
                params,
                Map.of(streamId, StreamEntryID.UNRECEIVED_ENTRY));

        StreamEntry entry = null;
        if (messages != null && !messages.isEmpty()) {
            List<StreamEntry> entries = messages.get(0).getValue();
            if (entries != null && !entries.isEmpty()) {
                entry = entries.get(0);
            }
        }
        if (entry != null) {
            log.trace("Redis stream id={}; read entry={}", streamId, entry);
        }
        return entry;
    }

    protected StreamEntry claimMessage(Jedis jedis, String streamId) {
        // Attempt to claim any pending messages that are idle for more than the threshold
        XAutoClaimParams params = new XAutoClaimParams()
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
        StreamEntryID startId = lastClaimCursor.getOrDefault(streamId, STREAM_ENTRY_ZERO);
        Map.Entry<StreamEntryID, List<StreamEntry>> messages;
        try {
            messages = jedis.xautoclaim(
                    streamId,
                    config.getDefaultConsumerGroupName(),
                    consumerName,
                    config.getClaimTimeoutMillis(),
                    startId,
                    params
            );
        } catch (JedisDataException e) {
            if (e.getMessage().contains("NOGROUP")) {
                // The group does not exist. We initialize it and avoid printing the exception
                log.info("Redis message stream - consume group={} do not exist", streamId);
                init(streamId);
            }
            throw e;
        }
        if (messages != null) {
            updateClaimCursor(streamId, messages.getKey());
        }

        StreamEntry entry = null;
        if (messages != null && messages.getValue() != null && !messages.getValue().isEmpty()) {
            entry = messages.getValue().get(0);
        }
        if (entry != null) {
            log.trace("Redis stream id={}; claimed entry={}", streamId, entry);
        }
        return entry;
    }

    /* Update the claim cursor for the next iteration. When xautoclaim reaches
       the end of the PEL, it returns "0-0" signaling wrap around to the beginning. */
    protected void updateClaimCursor(String streamId, StreamEntryID nextCursor) {
        if (nextCursor == null)
            return;
        if (STREAM_ENTRY_ZERO.equals(nextCursor))
            lastClaimCursor.remove(streamId);
        else
            lastClaimCursor.put(streamId, nextCursor);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int length(String streamId) {
        try (Jedis jedis = pool.getResource()) {
            return (int) jedis.xlen(streamId);
        }
    }
}
