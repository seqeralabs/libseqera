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

import groovy.transform.Memoized

/**
 * Configuration interface for message streams that defines timeout and consumer group settings
 * for stream-based message processing.
 * 
 * <p>This interface provides configuration parameters for:
 * <ul>
 *   <li>Consumer group management and default naming</li>
 *   <li>Message claim timeout handling for reliable delivery</li>
 *   <li>Consumer warning timeouts for monitoring purposes</li>
 * </ul>
 * 
 * <p>Implementations should provide appropriate values based on the underlying
 * message stream technology (e.g., Redis Streams) and application requirements.
 * 
 * <p>Example usage:
 * <pre>{@code
 * @Configuration
 * class MyStreamConfig implements RedisStreamConfig {
 *     String getDefaultConsumerGroupName() { return "my-service-group" }
 *     Duration getClaimTimeout() { return Duration.ofMinutes(5) }
 *     Duration getConsumerWarnTimeout() { return Duration.ofMinutes(2) }
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.1
 */
interface RedisStreamConfig {

    /**
     * Returns the default consumer group name used when creating message stream consumers
     * without an explicitly specified group.
     * 
     * @return the default consumer group name, must not be null or empty
     */
    String getDefaultConsumerGroupName()

    /**
     * Returns the timeout duration for claiming messages from the stream.
     * This timeout determines how long a consumer can hold a message before
     * it becomes available for claiming by other consumers.
     * 
     * @return the claim timeout duration, must be positive
     */
    Duration getClaimTimeout()

    /**
     * Returns the timeout duration after which a warning should be issued
     * if a consumer hasn't processed messages within this timeframe.
     * This is used for monitoring and alerting purposes.
     * 
     * @return the consumer warning timeout duration, must be positive
     */
    Duration getConsumerWarnTimeout()

    /**
     * Returns the claim timeout in milliseconds for convenience.
     * This is a derived value from {@link #getClaimTimeout()}.
     * 
     * @return the claim timeout in milliseconds
     */
    @Memoized
    default long getClaimTimeoutMillis() {
        return getClaimTimeout().toMillis()
    }

    /**
     * Returns the consumer warning timeout in milliseconds for convenience.
     * This is a derived value from {@link #getConsumerWarnTimeout()}.
     * 
     * @return the consumer warning timeout in milliseconds
     */
    @Memoized
    default long getConsumerWarnTimeoutMillis() {
        return getConsumerWarnTimeout().toMillis()
    }
}
