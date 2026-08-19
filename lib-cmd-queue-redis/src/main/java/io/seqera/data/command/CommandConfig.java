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
     * Admission cap: the maximum number of handler executions in flight at once.
     * The dispatcher stops claiming messages from the queue while the cap is
     * reached, resuming as tasks finish.
     *
     * <p>Replaces the implicit throttle the deleted 1-second execute-timeout used
     * to be: with fire-and-submit dispatch every delivery spawns a task immediately,
     * so without an explicit cap a backlog flood would spawn unbounded tasks.
     *
     * <p>Size it below the shared JDBC connection pool minus request-path headroom:
     * handler tasks make several sequential JDBC round-trips each, and bursts beyond
     * the cap should queue in the work queue, not in the connection pool.
     */
    default int maxConcurrency() {
        return 20;
    }

    /**
     * TTL (Time-To-Live) for command state records in the persistent store.
     * Commands expire and are removed after this duration.
     */
    default Duration stateTtl() {
        return Duration.ofDays(7);
    }

    /**
     * Bound on the compare-and-swap retry loop of a command state transition. A miss
     * means another writer transitioned the state between the read and the write; the
     * loop re-reads and re-applies, converging in one or two rounds under real
     * contention (at most a handful of writers ever touch one command). The bound is a
     * livelock backstop, not a throughput tunable — must be positive.
     */
    default int stateUpdateAttempts() {
        return 5;
    }

    /**
     * Re-poll cadence for commands whose handler declared PROCESSING: how often
     * {@code checkStatus()} is invoked while the async work is in flight.
     *
     * <p>Deliberately decoupled from the queue's visibility timeout, which paces crash
     * detection and transient-error retries: shortening that clock must not multiply
     * the polling load {@code checkStatus()} puts on its downstream dependencies
     * (database reads, cloud describe calls). A cadence at or below the visibility
     * timeout degrades to the claim cadence — the visibility timeout is the effective
     * floor.
     */
    default Duration checkStatusInterval() {
        return Duration.ofSeconds(45);
    }
}
