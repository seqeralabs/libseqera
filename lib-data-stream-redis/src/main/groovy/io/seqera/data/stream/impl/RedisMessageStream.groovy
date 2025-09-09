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

package io.seqera.data.stream.impl

import java.time.Duration

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Requires
import io.seqera.activator.redis.RedisActivator
import io.seqera.data.stream.MessageConsumer
import io.seqera.data.stream.MessageStream
import io.seqera.random.LongRndKey
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject
import jakarta.inject.Singleton
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.StreamEntryID
import redis.clients.jedis.exceptions.JedisDataException
import redis.clients.jedis.params.XAutoClaimParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
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
 * @param <String> the message type (currently fixed to String)
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
@Slf4j
@Requires(bean = RedisActivator)
@Singleton
@CompileStatic
class RedisMessageStream implements MessageStream<String> {

    private static final StreamEntryID STREAM_ENTRY_ZERO = new StreamEntryID("0-0")

    private static final String DATA_FIELD = 'data'

    @Inject
    private JedisPool pool

    @Inject
    private RedisStreamConfig config

    private String consumerName

    @PostConstruct
    private void create() {
        consumerName = "consumer-${LongRndKey.rndLong()}"
        log.info "Creating Redis message stream - consumer=${consumerName}"
    }

    protected boolean initGroup0(Jedis jedis, String streamId, String group) {
        log.debug "Initializing Redis group='$group'; streamId='$streamId'"
        try {
            jedis.xgroupCreate(streamId, group, STREAM_ENTRY_ZERO, true)
            return true
        }
        catch (JedisDataException e) {
            if (e.message.contains("BUSYGROUP")) {
                // The group already exists, so we can safely ignore this exception
                log.info "Redis message stream - consume group=$group already exists"
                return true
            }
            throw e
        }
    }

    @Override
    void init(String streamId) {
        log.info "Initializing Redis message stream=$streamId; consumer=${consumerName}; config=${config}"
        this.config = config
        try (Jedis jedis = pool.getResource()) {
            initGroup0(jedis, streamId, config.getDefaultConsumerGroupName())
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void offer(String streamId, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(streamId, StreamEntryID.NEW_ENTRY, Map.of(DATA_FIELD, message))
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean consume(String streamId, MessageConsumer<String> consumer) {
        try (Jedis jedis = pool.getResource()) {
            String msg
            final long begin = System.currentTimeMillis()
            final entry = claimMessage(jedis,streamId) ?: readMessage(jedis, streamId)
            if( entry && consumer.accept(msg=entry.getFields().get(DATA_FIELD)) ) {
                final tx = jedis.multi()
                // acknowledge the entry has been processed so that it cannot be claimed anymore
                tx.xack(streamId, config.getDefaultConsumerGroupName(), entry.getID())
                final delta = System.currentTimeMillis()-begin
                if( delta>config.consumerWarnTimeoutMillis ) {
                    log.warn "Redis message stream - consume processing took ${Duration.ofMillis(delta)} - offending entry=${entry.getID()}; message=${msg}"
                }
                // this remove permanently the entry from the stream
                tx.xdel(streamId, entry.getID())
                tx.exec()
                return true
            }
            else
                return false
        }
    }

    protected StreamEntry readMessage(Jedis jedis, String streamId) {
        // Create parameters for reading with a group
        final params = new XReadGroupParams()
                // Read one message at a time
                .count(1)

        // Read new messages from the stream using the correct xreadGroup signature
        List<Map.Entry<String, List<StreamEntry>>> messages = jedis.xreadGroup(
                config.getDefaultConsumerGroupName(),
                consumerName,
                params,
                Map.of(streamId, StreamEntryID.UNRECEIVED_ENTRY) )

        final entry = messages?.first()?.value?.first()
        if( entry!=null )
            log.trace "Redis stream id=$streamId; read entry=$entry"
        return entry
    }

    protected StreamEntry claimMessage(Jedis jedis, String streamId) {
        // Attempt to claim any pending messages that are idle for more than the threshold
        final params = new XAutoClaimParams()
                // claim one entry at time
                .count(1)
        def messages
        try {
            messages = jedis.xautoclaim(
                    streamId,
                    config.getDefaultConsumerGroupName(),
                    consumerName,
                    config.claimTimeoutMillis,
                    STREAM_ENTRY_ZERO,
                    params
            )
        } catch (JedisDataException e) {
            if (e.message.contains("NOGROUP")) {
                // The group does not exist. We initialize it and avoid printing the exception
                log.info "Redis message stream - consume group=$streamId do not exist"
                init(streamId)
            }
            throw e
        }
        final entry = messages?.getValue()?[0]
        if( entry!=null )
            log.trace "Redis stream id=$streamId; claimed entry=$entry"
        return entry
    }

    /**
     * {@inheritDoc}
     */
    @Override
    int length(String streamId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xlen(streamId)
        }
    }
}
