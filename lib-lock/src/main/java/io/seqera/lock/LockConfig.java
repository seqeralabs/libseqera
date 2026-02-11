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

package io.seqera.lock;

import java.time.Duration;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * Configuration for named lock managers.
 *
 * Supports multiple named configurations via:
 * <pre>
 * seqera:
 *   lock:
 *     cluster:
 *       auto-expire-duration: 5m
 *       acquire-retry-interval: 100ms
 *     task:
 *       auto-expire-duration: 1m
 * </pre>
 *
 * @author Paolo Di Tommaso
 */
@EachProperty("seqera.lock")
public class LockConfig {

    private static final Duration DEFAULT_AUTO_EXPIRE = Duration.ofMinutes(5);
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(100);

    private final String name;
    private Duration autoExpireDuration = DEFAULT_AUTO_EXPIRE;
    private Duration acquireRetryInterval = DEFAULT_RETRY_INTERVAL;

    public LockConfig(@Parameter String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Duration getAutoExpireDuration() {
        return autoExpireDuration;
    }

    public void setAutoExpireDuration(Duration autoExpireDuration) {
        this.autoExpireDuration = autoExpireDuration;
    }

    public Duration getAcquireRetryInterval() {
        return acquireRetryInterval;
    }

    public void setAcquireRetryInterval(Duration acquireRetryInterval) {
        this.acquireRetryInterval = acquireRetryInterval;
    }

    @Override
    public String toString() {
        return "LockConfig{name='" + name + "', autoExpireDuration=" + autoExpireDuration +
                ", acquireRetryInterval=" + acquireRetryInterval + '}';
    }
}