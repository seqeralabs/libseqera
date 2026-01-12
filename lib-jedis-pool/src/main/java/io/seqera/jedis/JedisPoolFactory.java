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

import java.net.URI;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.InvalidURIException;
import redis.clients.jedis.util.JedisURIHelper;

/**
 * Factory for creating a configured {@link JedisPool} bean.
 *
 * <p>Supports full Redis URI parsing including database selection (e.g., redis://host:6379/1),
 * SSL connections (rediss://), authentication, and connection pooling configuration.
 *
 * <p>When a {@link MeterRegistry} is available, pool metrics are automatically registered.
 *
 * @author Paolo Di Tommaso
 */
@Factory
@Requires(bean = RedisActivator.class)
public class JedisPoolFactory {

    private static final Logger log = LoggerFactory.getLogger(JedisPoolFactory.class);

    @Nullable
    @Inject
    private MeterRegistry meterRegistry;

    @Singleton
    public JedisPool createRedisPool(
            @Value("${redis.uri}") String connection,
            @Value("${redis.pool.minIdle:0}") int minIdle,
            @Value("${redis.pool.maxIdle:10}") int maxIdle,
            @Value("${redis.pool.maxTotal:50}") int maxTotal,
            @Value("${redis.client.timeout:5000}") int timeout,
            @Nullable @Value("${redis.password}") String password
    ) {
        final URI uri = URI.create(connection);
        final int database = JedisURIHelper.getDBIndex(uri);

        log.info("Creating Redis pool - uri={}; database={}; minIdle={}; maxIdle={}; maxTotal={}; timeout={}",
                maskPassword(connection), database, minIdle, maxIdle, maxTotal, timeout);

        // Pool config
        final JedisPoolConfig config = new JedisPoolConfig();
        config.setMinIdle(minIdle);
        config.setMaxIdle(maxIdle);
        config.setMaxTotal(maxTotal);

        // Client config with database support
        final JedisClientConfig clientConfig = clientConfig(uri, password, timeout);

        // Create the Jedis pool
        final JedisPool pool = new JedisPool(config, JedisURIHelper.getHostAndPort(uri), clientConfig);

        // Bind metrics if MeterRegistry is available
        if (meterRegistry != null) {
            new JedisPoolMetricsBinder(pool).bindTo(meterRegistry);
        }

        return pool;
    }

    /**
     * Creates the Jedis client configuration from the URI.
     *
     * @param uri      the Redis URI
     * @param password optional password override (if null, extracted from URI)
     * @param timeout  connection timeout in milliseconds
     * @return the configured JedisClientConfig
     */
    protected JedisClientConfig clientConfig(URI uri, String password, int timeout) {
        if (!JedisURIHelper.isValid(uri)) {
            throw new InvalidURIException("Invalid Redis connection URI: " + uri);
        }

        return DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeout)
                .socketTimeoutMillis(timeout)
                .blockingSocketTimeoutMillis(timeout)
                .user(JedisURIHelper.getUser(uri))
                .password(password != null ? password : JedisURIHelper.getPassword(uri))
                .database(JedisURIHelper.getDBIndex(uri))
                .protocol(JedisURIHelper.getRedisProtocol(uri))
                .ssl(JedisURIHelper.isRedisSSLScheme(uri))
                .build();
    }

    /**
     * Masks password in URI for logging purposes.
     */
    private String maskPassword(String uri) {
        return uri.replaceAll("://[^:]+:[^@]+@", "://****:****@");
    }
}
