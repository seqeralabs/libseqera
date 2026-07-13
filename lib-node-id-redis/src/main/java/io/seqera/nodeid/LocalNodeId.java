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

package io.seqera.nodeid;

import java.util.concurrent.atomic.AtomicLong;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory {@link NodeId} for local development and deployments without Redis.
 *
 * <p>Mirrors {@link RedisNodeId}: ordinals are drawn from a process-wide counter rotated
 * modulo {@code capacity}, the in-memory counterpart of the Redis {@code INCR} counter. A
 * single-replica process yields {@code 0}. Activated when no {@link RedisActivator} bean is
 * present.
 *
 * @author Paolo Di Tommaso
 */
@Singleton
@Requires(missingBeans = RedisActivator.class)
public class LocalNodeId implements NodeId {

    private static final Logger log = LoggerFactory.getLogger(LocalNodeId.class);

    private static final AtomicLong COUNTER = new AtomicLong();

    private final int value;
    private final int capacity;

    @Inject
    public LocalNodeId(@Value("${seqera.node-id.capacity:1024}") int capacity) {
        this.capacity = capacity;
        this.value = Math.floorMod(COUNTER.getAndIncrement(), capacity);
        log.info("Using local node id: value={}, capacity={}", value, capacity);
    }

    @Override
    public int value() {
        return value;
    }

    @Override
    public int capacity() {
        return capacity;
    }
}
