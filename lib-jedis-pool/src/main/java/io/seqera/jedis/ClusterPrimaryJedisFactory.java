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

import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * Pool object factory for {@link RedisMode#CLUSTER_PRIMARY}: creates connections to the current
 * cluster primary and validates them with {@code ROLE} instead of {@code PING}.
 *
 * <p>A replica answers {@code PING}, so a {@code PING} check keeps serving a connection that every
 * keyed command will reject with {@code MOVED} — including a connection to a primary demoted by a
 * failover. A failed {@code ROLE} check evicts the connection and makes the connector look the
 * primary up again, so the pool converges on the new primary within one borrow per stale connection.
 *
 * @author Paolo Di Tommaso
 */
class ClusterPrimaryJedisFactory implements PooledObjectFactory<Jedis> {

    private static final Logger log = LoggerFactory.getLogger(ClusterPrimaryJedisFactory.class);

    private final JedisConnector connector;

    ClusterPrimaryJedisFactory(JedisConnector connector) {
        this.connector = connector;
    }

    @Override
    public PooledObject<Jedis> makeObject() {
        return new DefaultPooledObject<>(new Jedis(connector.target(), connector.clientConfig()));
    }

    @Override
    public boolean validateObject(PooledObject<Jedis> pooled) {
        final Jedis jedis = pooled.getObject();
        if (connector.isPrimarySafe(jedis)) {
            return true;
        }
        log.debug("Evicting Redis connection - not on the primary");
        connector.invalidate();
        return false;
    }

    @Override
    public void destroyObject(PooledObject<Jedis> pooled) {
        try {
            pooled.getObject().disconnect();
        }
        catch (Exception e) {
            log.debug("Error closing Redis connection: {}", e.getMessage());
        }
    }

    @Override
    public void activateObject(PooledObject<Jedis> pooled) {
        // nothing to reset: cluster mode has only database 0
    }

    @Override
    public void passivateObject(PooledObject<Jedis> pooled) {
    }
}
