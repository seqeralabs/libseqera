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
import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Bean;
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

    // preDestroy = 'close' makes Micronaut close the pool during bean disposal (reverse
    // dependency-injection order), so beans that injected this pool are guaranteed to have
    // run their @PreDestroy first. Without it the pool is never closed — JedisPool is
    // Closeable but not a Micronaut LifeCycle, so nothing disposes it — and background
    // consumer threads can call getResource() on a pool whose Redis connections are gone.
    @Singleton
    @Bean(preDestroy = "close")
    public JedisPool createRedisPool(
            @Value("${redis.uri}") String connection,
            @Value("${redis.pool.minIdle:0}") int minIdle,
            @Value("${redis.pool.maxIdle:10}") int maxIdle,
            @Value("${redis.pool.maxTotal:50}") int maxTotal,
            @Value("${redis.pool.testOnBorrow:false}") boolean testOnBorrow,
            @Value("${redis.pool.maxWait:-1}") long maxWait,
            @Value("${redis.client.timeout:5000}") int timeout,
            @Value("${redis.client.blockingTimeout:-1}") int blockingTimeout,
            @Nullable @Value("${redis.password}") String password
    ) {
        final URI uri = URI.create(connection);
        if (!JedisURIHelper.isValid(uri)) {
            throw new InvalidURIException("Invalid Redis connection URI: " + uri);
        }
        final int database = JedisURIHelper.getDBIndex(uri);

        log.info("Creating Redis pool - uri={}; database={}; minIdle={}; maxIdle={}; maxTotal={}; testOnBorrow={}; maxWait={}; timeout={}; blockingTimeout={}",
                maskPassword(connection), database, minIdle, maxIdle, maxTotal, testOnBorrow, maxWait, timeout, blockingTimeout);

        // Pool config
        final JedisPoolConfig config = new JedisPoolConfig();
        config.setMinIdle(minIdle);
        config.setMaxIdle(maxIdle);
        config.setMaxTotal(maxTotal);
        // PING-validate connections on borrow so a RESP-desynced connection is evicted
        // instead of served to the next caller — see libseqera#92 / platform#11820
        config.setTestOnBorrow(testOnBorrow);
        // Bound on a borrow against an exhausted pool, in milliseconds. The commons-pool2
        // default (-1) blocks INDEFINITELY: a borrower on an exhausted pool never throws,
        // which can silently freeze periodic work (e.g. a single-threaded scheduler whose
        // tick borrows a connection — sched PR #913 review). Set a bound so exhaustion
        // surfaces as an exception the caller can retry, not an invisible hang. -1 keeps
        // the pre-existing unbounded behavior.
        config.setMaxWait(Duration.ofMillis(maxWait));

        // Client config with database support
        final JedisClientConfig clientConfig = clientConfig(uri, password, timeout, blockingTimeout);

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
     * @param uri             the Redis URI
     * @param password        optional password override (blank or null → extracted from URI)
     * @param timeout         connection and socket timeout in milliseconds
     * @param blockingTimeout socket timeout applied to blocking reads (pub/sub subscribe,
     *                        BLPOP/BRPOP, XREAD BLOCK) in milliseconds; 0 means no timeout
     *                        and any negative value inherits {@code timeout}
     * @return the configured JedisClientConfig
     */
    protected JedisClientConfig clientConfig(URI uri, String password, int timeout, int blockingTimeout) {
        if (!JedisURIHelper.isValid(uri)) {
            throw new InvalidURIException("Invalid Redis connection URI: " + uri);
        }

        return DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeout)
                .socketTimeoutMillis(timeout)
                // Jedis applies this timeout only while a blocking read is in flight, and treats
                // 0 as "no timeout" — which a long-lived pub/sub subscriber needs, since it sits
                // idle on the socket between messages and would otherwise be torn down every
                // `timeout` ms. Negative inherits `timeout` to keep the previous behavior.
                .blockingSocketTimeoutMillis(blockingTimeout < 0 ? timeout : blockingTimeout)
                .user(JedisURIHelper.getUser(uri))
                // an empty override means "no password configured" — passing it through would make
                // Jedis send AUTH "" and fail every connection against a password-less Redis
                .password(password != null && !password.isBlank() ? password : JedisURIHelper.getPassword(uri))
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
