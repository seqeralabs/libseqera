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
import io.micronaut.context.annotation.Value
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
import redis.clients.jedis.params.XPendingParams
import redis.clients.jedis.params.XReadGroupParams
import redis.clients.jedis.resps.StreamEntry
import redis.clients.jedis.resps.StreamGroupInfo

/**
 * Implement a distributed {@link MessageStream} backed by a Redis stream.
 * This implementation allows multiple concurrent consumers and guarantee consistency
 * across replicas restart. 
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@Requires(property = 'redis.uri')
@Singleton
@CompileStatic
class RedisMessageStream implements MessageStream<String> {

    private static final StreamEntryID STREAM_ENTRY_ZERO = new StreamEntryID("0-0")

    private static final String DATA_FIELD = 'data'

    @Inject
    private JedisPool pool

    @Value('${wave.message-stream.claim-timeout:5s}')
    private Duration claimTimeout

    @Value('${wave.message-stream.consume-warn-timeout-millis:4000}')
    private long consumeWarnTimeoutMillis

    private String consumerName

    private Set<String> consumerGroups = new HashSet<>()

    @PostConstruct
    private void create() {
        consumerName = "consumer-${LongRndKey.rndLong()}"

        log.info "Creating Redis message stream - consumer=${consumerName}; claim-timeout=${claimTimeout}"
    }

    protected boolean initGroup0(Jedis jedis, String streamId, String group) {
        log.debug "Initializing Redis group='$group'; streamId='$streamId'"
        try {
            jedis.xgroupCreate(streamId, group, STREAM_ENTRY_ZERO, true)
            jedis.xgroupCreateConsumer(streamId, group, "${consumerName}-${group}")
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

    /**
     * {@inheritDoc}
     */
    @Override
    void init(String streamId, String groupId) {
        try (Jedis jedis = pool.getResource()) {
            initGroup0(jedis, streamId, groupId)
            consumerGroups.add(groupId)
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void offer(String streamId, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.xadd(streamId, StreamEntryID.NEW_ENTRY, Map.of(DATA_FIELD, message))
            log.debug "Added message to streamId=$streamId"
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean consume(String streamId, String groupId, MessageConsumer<String> consumer) {
        try (Jedis jedis = pool.getResource()) {
            String msg
            final long begin = System.currentTimeMillis()
            final entry = claimMessage(jedis,streamId, groupId) ?: readMessage(jedis, streamId, groupId)
            if( entry && consumer.accept(msg=entry.getFields().get(DATA_FIELD)) ) {
                // acknowledge the entry has been processed so that it cannot be claimed anymore
                jedis.xack(streamId, groupId, entry.getID())
                final delta = System.currentTimeMillis()-begin
                if( delta>consumeWarnTimeoutMillis ) {
                    log.warn "Redis message stream - consume processing took ${Duration.ofMillis(delta)} - offending entry=${entry.getID()}; message=${msg}"
                }
                
                // Check if all consumer groups have processed this message before deleting
                if( consumerGroups.size() == 1 || allGroupsProcessedMessage(jedis, streamId, entry.getID()) ) {
                    jedis.xdel(streamId, entry.getID())
                    log.trace "Deleted message ${entry.getID()} from stream ${streamId} after all groups processed it"
                }
                
                return true
            }
            else
                return false
        }
    }

    protected StreamEntry readMessage(Jedis jedis, String streamId, String groupId) {
        // Create parameters for reading with a group
        final params = new XReadGroupParams()
                // Read one message at a time
                .count(1)

        // Read new messages from the stream using the correct xreadGroup signature
        List<Map.Entry<String, List<StreamEntry>>> messages = jedis.xreadGroup(
                groupId,
                "${consumerName}-${groupId}",
                params,
                Map.of(streamId, StreamEntryID.UNRECEIVED_ENTRY) )

        final entry = messages?.first()?.value?.first()
        if( entry!=null )
            log.trace "Redis stream id=$streamId; read entry=$entry"
        return entry
    }

    protected StreamEntry claimMessage(Jedis jedis, String streamId, String groupId) {
        // Attempt to claim any pending messages that are idle for more than the threshold
        final params = new XAutoClaimParams()
                // claim one entry at time
                .count(1)
        final messages = jedis.xautoclaim(
                streamId,
                groupId,
                "${consumerName}-${groupId}",
                claimTimeout.toMillis(),
                STREAM_ENTRY_ZERO,
                params
        )
        final entry = messages?.getValue()?[0]
        if( entry!=null )
            log.trace "Redis stream id=$streamId; claimed entry=$entry"
        return entry
    }

    /**
     * Check if all consumer groups for a stream have processed (acknowledged) the given message.
     * This is determined by checking if the message ID is not present in any group's pending entries list.
     */
    protected static boolean allGroupsProcessedMessage(Jedis jedis, String streamId, StreamEntryID messageId) {
        try {
            // Get all consumer groups for this stream
            final groups = jedis.xinfoGroups(streamId)
            for (StreamGroupInfo group : groups) {
                if (group.lastDeliveredId < messageId) {
                    return false
                }
                final pending = jedis.xpending(streamId, group.getName(), XPendingParams.xPendingParams(messageId.toString(), messageId.toString(), 1))
                if (!pending.isEmpty()) {
                    return false
                }
            }
            return true
        } catch (Exception e) {
            log.warn "Error checking if all groups processed message ${messageId} in stream ${streamId}: ${e.message}"
            // In case of error, don't delete the message to be safe
            return false
        }
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
