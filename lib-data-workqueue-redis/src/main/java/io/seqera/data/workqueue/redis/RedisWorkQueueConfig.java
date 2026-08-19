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
 * Configuration interface for work queues that defines timeout and consumer group settings
 * for queue-based message processing.
 *
 * <p>This interface provides configuration parameters for:
 * <ul>
 *   <li>Consumer group management and default naming</li>
 *   <li>Message visibility timeout handling for reliable delivery</li>
 *   <li>Consumer warning timeouts for monitoring purposes</li>
 * </ul>
 *
 * <p>Implementations should provide appropriate values based on the underlying
 * queue technology (e.g., Redis Streams) and application requirements.
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
 * @since 1.1
 */
public interface RedisWorkQueueConfig {

    /**
     * Returns the default consumer group name used when creating queue consumers
     * without an explicitly specified group.
     *
     * @return the default consumer group name, must not be null or empty
     */
    String getDefaultConsumerGroupName();

    /**
     * Returns the visibility timeout for messages handed to a consumer.
     * This timeout determines how long a consumer can hold a message before
     * it becomes available for claiming by other consumers.
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
     * Period of the lease-renewal tick that keeps in-flight entries from going stalled.
     *
     * <p>Defaults to a quarter of the visibility timeout, so the margin math tracks a
     * re-tuned visibility timeout automatically: with a successful renewal at {@code t0},
     * ticks fire at {@code t0+P, t0+2P, t0+3P} while the entry becomes claimable at
     * {@code t0+4P} — two consecutive failed or missed ticks are tolerated with a
     * quarter-timeout margin remaining. Must be well below the visibility timeout: a
     * period at or above it means every lease is claimable before its first renewal.
     *
     * @return the lease renewal period, must be positive and below the visibility timeout
     */
    default Duration getLeaseRenewalPeriod() {
        return getVisibilityTimeout().dividedBy(4);
    }

    /**
     * Returns the lease renewal period in milliseconds for convenience.
     * This is a derived value from {@link #getLeaseRenewalPeriod()}.
     *
     * @return the lease renewal period in milliseconds
     */
    default long getLeaseRenewalPeriodMillis() {
        return getLeaseRenewalPeriod().toMillis();
    }

    /**
     * Age past which an unsettled lease whose owner is not provably alive is treated as a
     * registry leak: renewal stops, loudly, so the claim cycle can recover the entry.
     * A lease whose bound owner is still running is never age-pruned, however long it
     * runs — this bound only limits how long a leaked registration can keep an entry
     * from going stalled.
     *
     * <p>Defaults to three visibility timeouts.
     *
     * @return the maximum lease age, must be positive
     */
    default Duration getMaxLeaseAge() {
        return getVisibilityTimeout().multipliedBy(3);
    }

    /**
     * Returns the maximum lease age in milliseconds for convenience.
     * This is a derived value from {@link #getMaxLeaseAge()}.
     *
     * @return the maximum lease age in milliseconds
     */
    default long getMaxLeaseAgeMillis() {
        return getMaxLeaseAge().toMillis();
    }
}
