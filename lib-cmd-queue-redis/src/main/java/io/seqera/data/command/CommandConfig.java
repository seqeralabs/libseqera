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
     * TTL (Time-To-Live) for command state records in the persistent store.
     * Commands expire and are removed after this duration.
     */
    default Duration stateTtl() {
        return Duration.ofDays(7);
    }

    /**
     * Maximum number of commands that may be in flight on a single instance at once.
     * Handlers run on virtual threads, so this is a memory/heartbeat ceiling (the underlying
     * work queue's in-flight semaphore), not a thread-pool size; commands beyond it wait in
     * the queue (backpressure). Effective minimum is 1.
     */
    default int concurrency() {
        return 1000;
    }
}
