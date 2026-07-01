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
package io.seqera.data.command;

import java.time.Duration;

/**
 * Configuration interface for command queue processing.
 *
 * <p>Provides default values for all configuration options. Applications
 * can implement this interface to provide custom values via their
 * preferred configuration mechanism (e.g., @Value annotations).
 *
 * @author Paolo Di Tommaso
 */
public interface CommandConfig {

    /**
     * Interval for polling the command queue.
     */
    default Duration pollInterval() {
        return Duration.ofSeconds(1);
    }

    /**
     * @deprecated No longer consulted. Command handlers now run to completion on a bounded worker
     * pool (see {@link CommandHandler#execute}); there is no synchronous execution timeout. Retained
     * only for source/binary backward compatibility. Will be removed in a future major release.
     */
    @Deprecated
    default Duration executeTimeout() {
        return Duration.ofSeconds(1);
    }

    /**
     * Number of worker threads used to execute command handlers concurrently, off the queue poll
     * thread. Also caps the number of commands executing at once per replica.
     */
    default int commandPoolSize() {
        return 10;
    }

    /**
     * Bounded capacity of the worker pool queue. When full, newly delivered commands are left in the
     * message queue (not acknowledged) and retried on a later delivery, providing backpressure.
     */
    default int commandPoolQueueSize() {
        return 100;
    }

    /**
     * Time-to-live of the per-command single-runner lock. While the holding replica is alive the
     * lock is auto-renewed (watchdog) so it is effectively held for the whole execution; if the
     * replica dies, the lock expires after this duration and another replica can take over. Set it
     * comfortably larger than any expected JVM pause or Redis partition.
     */
    default Duration commandLockDuration() {
        return Duration.ofMinutes(1);
    }

    /**
     * TTL (Time-To-Live) for command state records in the persistent store.
     * Commands expire and are removed after this duration.
     */
    default Duration stateTtl() {
        return Duration.ofDays(7);
    }
}
