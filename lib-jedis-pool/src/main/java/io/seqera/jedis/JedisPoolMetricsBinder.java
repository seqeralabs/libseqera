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
package io.seqera.jedis;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import redis.clients.jedis.JedisPool;

/**
 * Binds {@link JedisPool} metrics to a {@link MeterRegistry}.
 *
 * <p>Registers the following metrics:
 * <ul>
 *   <li>jedis.pool.active - Number of active connections</li>
 *   <li>jedis.pool.idle - Number of idle connections</li>
 *   <li>jedis.pool.waiters - Number of threads waiting for a connection</li>
 *   <li>jedis.pool.created - Total connections created</li>
 *   <li>jedis.pool.destroyed - Total connections destroyed</li>
 *   <li>jedis.pool.borrowed - Total connections borrowed</li>
 *   <li>jedis.pool.returned - Total connections returned</li>
 *   <li>jedis.pool.max.borrow.wait.millis - Maximum borrow wait time</li>
 *   <li>jedis.pool.mean.borrow.wait.millis - Mean borrow wait time</li>
 *   <li>jedis.pool.mean.active.millis - Mean active duration</li>
 *   <li>jedis.pool.mean.idle.millis - Mean idle duration</li>
 * </ul>
 *
 * @author Paolo Di Tommaso
 */
public class JedisPoolMetricsBinder implements MeterBinder {

    private final JedisPool pool;

    public JedisPoolMetricsBinder(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // Pool state metrics
        registry.gauge("jedis.pool.active", pool, JedisPool::getNumActive);
        registry.gauge("jedis.pool.idle", pool, JedisPool::getNumIdle);
        registry.gauge("jedis.pool.waiters", pool, JedisPool::getNumWaiters);

        // Connection lifecycle metrics
        registry.gauge("jedis.pool.created", pool, JedisPool::getCreatedCount);
        registry.gauge("jedis.pool.destroyed", pool, JedisPool::getDestroyedCount);

        // Borrow/Return statistics
        registry.gauge("jedis.pool.borrowed", pool, JedisPool::getBorrowedCount);
        registry.gauge("jedis.pool.returned", pool, JedisPool::getReturnedCount);

        // Timing metrics
        registry.gauge("jedis.pool.max.borrow.wait.millis", pool,
                p -> p.getMaxBorrowWaitDuration().toMillis());
        registry.gauge("jedis.pool.mean.borrow.wait.millis", pool,
                p -> p.getMeanBorrowWaitDuration().toMillis());
        registry.gauge("jedis.pool.mean.active.millis", pool,
                p -> p.getMeanActiveDuration().toMillis());
        registry.gauge("jedis.pool.mean.idle.millis", pool,
                p -> p.getMeanIdleDuration().toMillis());
    }
}
