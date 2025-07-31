/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2025, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
