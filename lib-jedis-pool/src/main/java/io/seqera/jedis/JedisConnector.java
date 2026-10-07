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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.exceptions.JedisMovedDataException;
import redis.clients.jedis.resps.ClusterShardInfo;
import redis.clients.jedis.resps.ClusterShardNodeInfo;

/**
 * Opens connections to the Redis server named by {@code redis.uri}, honoring {@code redis.mode}.
 *
 * <p>In {@link RedisMode#STANDALONE} mode every connection goes to the URI host. In
 * {@link RedisMode#CLUSTER_PRIMARY} mode the URI host is only a discovery endpoint (e.g. a MemoryDB
 * {@code clustercfg.} record, which resolves to every node of the shard, replicas included): the
 * primary is looked up with {@code CLUSTER SHARDS}, cached, and looked up again after
 * {@link #invalidate(HostAndPort)}. A standalone client cannot follow {@code MOVED}, so a connection that lands
 * on a replica fails every keyed command; this class makes sure connections land on the primary.
 *
 * <p>Pooled connections come from the {@code JedisPool} bean. Use {@link #connect()} for a dedicated
 * connection that must not time out while blocked ({@code XREAD BLOCK}, {@code SUBSCRIBE}).
 *
 * @author Paolo Di Tommaso
 */
public class JedisConnector {

    private static final Logger log = LoggerFactory.getLogger(JedisConnector.class);

    /**
     * Key read to tell the primary from a replica. Never written: only its slot matters, and in a
     * single-shard cluster the primary serves them all.
     */
    static final String PROBE_KEY = "jedis-pool:primary-probe";

    private final HostAndPort seed;
    private final JedisClientConfig clientConfig;
    private final RedisMode mode;
    private final AtomicReference<HostAndPort> primary = new AtomicReference<>();

    /**
     * @param seed         the host in {@code redis.uri}
     * @param clientConfig the client config shared by every connection
     * @param mode         how connections reach the server
     */
    public JedisConnector(HostAndPort seed, JedisClientConfig clientConfig, RedisMode mode) {
        this.seed = seed;
        this.clientConfig = clientConfig;
        this.mode = mode;
    }

    public RedisMode mode() {
        return mode;
    }

    public JedisClientConfig clientConfig() {
        return clientConfig;
    }

    /**
     * The node new connections go to: the URI host, or the cluster primary in
     * {@link RedisMode#CLUSTER_PRIMARY} mode.
     */
    public HostAndPort target() {
        if (mode == RedisMode.STANDALONE) {
            return seed;
        }
        HostAndPort result = primary.get();
        if (result == null) {
            result = discoverPrimary();
            primary.set(result);
        }
        return result;
    }

    /**
     * Forget the cached primary if it is still {@code node}, so the next connection looks it up again.
     * Called when a connection to {@code node} fails or turns out not to be on the primary. Conditional
     * so that, after a failover, the many stale connections evicted one by one do not each throw away
     * a primary that another caller has already rediscovered.
     *
     * @param node the node a connection was opened to
     */
    public void invalidate(HostAndPort node) {
        if (primary.compareAndSet(node, null)) {
            log.debug("Redis cluster primary {} invalidated", node);
        }
    }

    /**
     * Open a dedicated, non-pooled connection to {@link #target()} whose blocking commands never time
     * out. The caller owns the connection and must close it.
     *
     * <p>In {@link RedisMode#CLUSTER_PRIMARY} mode a connection that fails, or lands on a node that is
     * no longer the primary, is retried once after looking the primary up again, so a caller opening
     * a connection during or just after a failover reaches the new primary instead of failing.
     *
     * @return a connected Jedis instance
     */
    public Jedis connect() {
        final JedisClientConfig config = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(clientConfig.getConnectionTimeoutMillis())
                .socketTimeoutMillis(clientConfig.getSocketTimeoutMillis())
                .blockingSocketTimeoutMillis(0)
                .user(clientConfig.getUser())
                .password(clientConfig.getPassword())
                .database(clientConfig.getDatabase())
                .protocol(clientConfig.getRedisProtocol())
                .ssl(clientConfig.isSsl())
                .hostAndPortMapper(clientConfig.getHostAndPortMapper())
                .build();
        if (mode == RedisMode.STANDALONE) {
            return new Jedis(seed, config);
        }
        try {
            return connectPrimary(config);
        }
        catch (JedisConnectionException e) {
            log.debug("Redis dedicated connection failed, retrying after primary rediscovery: {}", e.getMessage());
            return connectPrimary(config);
        }
    }

    /**
     * Connect to the current primary, invalidating it when the connection fails or lands on a replica.
     *
     * @param config the client config to connect with
     * @return a connection on the primary
     * @throws JedisConnectionException when the node is unreachable or not the primary
     */
    Jedis connectPrimary(JedisClientConfig config) {
        final HostAndPort node = target();
        final Jedis jedis;
        try {
            jedis = new Jedis(node, config);
        }
        catch (JedisConnectionException e) {
            invalidate(node);
            throw e;
        }
        if (!isPrimarySafe(jedis)) {
            jedis.close();
            invalidate(node);
            throw new JedisConnectionException("Redis node " + node + " is not the primary");
        }
        return jedis;
    }

    /**
     * Whether the connection is on the primary. Connection errors count as {@code false}; any other
     * error, e.g. an ACL {@code NOPERM} on the probe key, is logged at WARN, as it fails every check.
     */
    boolean isPrimarySafe(Jedis jedis) {
        try {
            return jedis.isConnected() && isPrimary(jedis);
        }
        catch (JedisConnectionException e) {
            log.debug("Redis primary check failed: {}", e.getMessage());
            return false;
        }
        catch (JedisDataException e) {
            log.warn("Redis primary check failed - the redis user needs read access to key '{}': {}", PROBE_KEY, e.getMessage());
            return false;
        }
    }

    /**
     * Whether the connection is on the primary, by reading {@link #PROBE_KEY}. In a single-shard cluster
     * the primary serves every slot, while a replica answers a keyed command with {@code MOVED} (no
     * {@code READONLY} is ever sent). Unlike {@code ROLE} ({@code @admin @dangerous}) this needs only
     * {@code @read}, so it works for hardened ACL users, and it costs one round-trip like {@code PING}.
     *
     * @throws JedisDataException for an error other than {@code MOVED}, e.g. {@code NOPERM}
     */
    static boolean isPrimary(Jedis jedis) {
        try {
            jedis.exists(PROBE_KEY);
            return true;
        }
        catch (JedisMovedDataException e) {
            return false;
        }
    }

    /**
     * Ask the discovery endpoint which node is the primary. Tries every address the endpoint resolves
     * to, since one of them may be the node that just failed, and confirms the answer with a probe:
     * right after a failover {@code CLUSTER SHARDS} can still list the demoted node as a primary next
     * to the promoted one.
     */
    protected HostAndPort discoverPrimary() {
        final List<HostAndPort> rejected = new ArrayList<>();
        JedisException last = null;
        for (HostAndPort candidate : seedAddresses()) {
            try {
                final List<HostAndPort> primaries;
                try (Jedis jedis = new Jedis(candidate, clientConfig)) {
                    primaries = primariesOf(jedis.clusterShards(), clientConfig.isSsl());
                }
                for (HostAndPort node : primaries) {
                    if (confirmPrimary(node)) {
                        log.info("Redis cluster primary is {} (discovered via {})", node, candidate);
                        return node;
                    }
                    rejected.add(node);
                }
            }
            catch (JedisException e) {
                log.debug("Redis primary discovery via {} failed: {}", candidate, e.getMessage());
                last = e;
            }
        }
        final String detail = rejected.isEmpty() ? "" : " - not the primary: " + rejected;
        throw new JedisConnectionException("Unable to discover the Redis cluster primary via " + seed + detail, last);
    }

    /**
     * Whether {@code node} is reachable and the primary. Errors other than connection failures, e.g.
     * {@code NOPERM}, propagate so discovery reports them as its cause.
     */
    private boolean confirmPrimary(HostAndPort node) {
        try (Jedis jedis = new Jedis(node, clientConfig)) {
            return isPrimary(jedis);
        }
        catch (JedisConnectionException e) {
            log.debug("Redis node {} unreachable: {}", node, e.getMessage());
            return false;
        }
    }

    private List<HostAndPort> seedAddresses() {
        try {
            final InetAddress[] addresses = InetAddress.getAllByName(seed.getHost());
            if (addresses.length > 1) {
                final List<HostAndPort> result = new ArrayList<>(addresses.length);
                for (InetAddress address : addresses) {
                    result.add(new HostAndPort(address.getHostAddress(), seed.getPort()));
                }
                return result;
            }
        }
        catch (UnknownHostException e) {
            log.debug("Unable to resolve Redis host {}: {}", seed.getHost(), e.getMessage());
        }
        return List.of(seed);
    }

    /**
     * The online nodes a {@code CLUSTER SHARDS} reply lists as primary, for a single-shard cluster.
     * Usually one; briefly two right after a failover.
     *
     * @param shards the reply
     * @param ssl    whether connections use TLS, which selects {@code tls-port} when the node reports one
     * @return the primaries' addresses
     * @throws JedisException when the cluster has more than one shard, or no online primary
     */
    static List<HostAndPort> primariesOf(List<ClusterShardInfo> shards, boolean ssl) {
        if (shards == null || shards.size() != 1) {
            throw new JedisException("redis.mode=cluster-primary requires a single-shard cluster - found " + (shards == null ? 0 : shards.size()) + " shards");
        }
        final List<HostAndPort> result = new ArrayList<>(1);
        for (ClusterShardNodeInfo node : shards.getFirst().getNodes()) {
            if ("master".equals(node.getRole()) && "online".equals(node.getHealth())) {
                result.add(new HostAndPort(hostOf(node), portOf(node, ssl)));
            }
        }
        if (result.isEmpty()) {
            throw new JedisConnectionException("No online primary in the Redis cluster shard");
        }
        return result;
    }

    private static String hostOf(ClusterShardNodeInfo node) {
        final String endpoint = node.getEndpoint();
        // Redis reports '?' when the preferred endpoint type is unknown
        return endpoint != null && !endpoint.isEmpty() && !"?".equals(endpoint) ? endpoint : node.getIp();
    }

    private static int portOf(ClusterShardNodeInfo node, boolean ssl) {
        final Long tlsPort = node.getTlsPort();
        final Long port = node.getPort();
        if (ssl && tlsPort != null && tlsPort > 0) {
            return tlsPort.intValue();
        }
        // a TLS-only node reports just 'tls-port'
        return port != null && port > 0 ? port.intValue() : tlsPort.intValue();
    }

    @Override
    public String toString() {
        return "JedisConnector[mode=" + mode + "; seed=" + seed + "]";
    }
}
