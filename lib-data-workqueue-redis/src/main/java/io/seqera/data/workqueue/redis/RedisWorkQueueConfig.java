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

/**
 * Configuration interface for Redis-backed work queues that defines timeout and consumer
 * group settings for reliable message processing.
 *
 * <p>This interface provides configuration parameters for:
 * <ul>
 *   <li>Consumer group management and default naming</li>
 *   <li>Message visibility timeout handling for reliable delivery</li>
 *   <li>Consumer warning timeouts for monitoring purposes</li>
 * </ul>
 *
 * <p>Implementations should provide appropriate values based on the underlying
 * work-queue technology (Redis Streams consumer groups) and application requirements.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Configuration
 * class MyQueueConfig implements RedisWorkQueueConfig {
 *     String getDefaultConsumerGroupName() { return "my-service-group"; }
 *     Duration getVisibilityTimeout() { return Duration.ofMinutes(5); }
 *     Duration getConsumerWarnTimeout() { return Duration.ofMinutes(2); }
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
public interface RedisWorkQueueConfig {

    /**
     * Returns the default consumer group name used when creating work queue consumers
     * without an explicitly specified group.
     *
     * @return the default consumer group name, must not be null or empty
     */
    String getDefaultConsumerGroupName();

    /**
     * Returns the visibility timeout duration for messages delivered from the queue.
     * This timeout determines how long a consumer can hold a message before
     * it becomes available for reclaiming by other consumers (mapped to the Redis
     * consumer-group min-idle used by {@code XAUTOCLAIM}). Backed by the
     * {@code visibility-timeout} configuration property.
     *
     * @return the visibility timeout duration, must be positive
     */
    Duration getVisibilityTimeout();

    /**
     * Returns the timeout duration after which a warning should be issued
     * if a consumer hasn't processed messages within this timeframe.
     * This is used for monitoring and alerting purposes.
     *
     * @return the consumer warning timeout duration, must be positive
     */
    Duration getConsumerWarnTimeout();

    /**
     * Returns the visibility timeout in milliseconds for convenience.
     * This is a derived value from {@link #getVisibilityTimeout()}.
     *
     * @return the visibility timeout in milliseconds
     */
    default long getVisibilityTimeoutMillis() {
        return getVisibilityTimeout().toMillis();
    }

    /**
     * Returns the consumer warning timeout in milliseconds for convenience.
     * This is a derived value from {@link #getConsumerWarnTimeout()}.
     *
     * @return the consumer warning timeout in milliseconds
     */
    default long getConsumerWarnTimeoutMillis() {
        return getConsumerWarnTimeout().toMillis();
    }

    /**
     * Returns how often in-flight leases are renewed (heartbeated) to keep them
     * from being reclaimed by peer consumers while a handler is still running.
     * Must be shorter than {@link #getVisibilityTimeout()}; defaults to {@code visibility-timeout / 3}
     * so that up to two consecutive misses are tolerated.
     *
     * @return the heartbeat interval duration
     */
    default Duration getHeartbeatInterval() {
        return getVisibilityTimeout().dividedBy(3);
    }

    /**
     * Returns the heartbeat interval in milliseconds for convenience.
     * This is a derived value from {@link #getHeartbeatInterval()}.
     *
     * @return the heartbeat interval in milliseconds
     */
    default long getHeartbeatIntervalMillis() {
        return getHeartbeatInterval().toMillis();
    }

    /**
     * Returns the upper bound on a single {@code accept()} invocation before its
     * lease is released (safety valve). This bounds one handler invocation, not the
     * total lease lifetime; past this bound the heartbeat daemon stops renewing the
     * lease so it becomes reclaimable. Defaults to {@code 15m}.
     *
     * @return the maximum single-invocation processing time duration
     */
    default Duration getMaxProcessingTime() {
        return Duration.ofMinutes(15);
    }

    /**
     * Returns the maximum processing time in milliseconds for convenience.
     * This is a derived value from {@link #getMaxProcessingTime()}.
     *
     * @return the maximum processing time in milliseconds
     */
    default long getMaxProcessingTimeMillis() {
        return getMaxProcessingTime().toMillis();
    }
}
