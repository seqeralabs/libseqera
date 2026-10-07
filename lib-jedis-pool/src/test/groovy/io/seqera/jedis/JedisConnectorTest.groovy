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
package io.seqera.jedis

import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.HostAndPortMapper
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisClientConfig
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisException
import redis.clients.jedis.resps.ClusterShardInfo
import redis.clients.jedis.resps.ClusterShardNodeInfo

/**
 * Tests for {@link JedisConnector} and {@link ClusterPrimaryJedisFactory}, against a real single-shard
 * cluster of one primary and one replica — the topology of a MemoryDB cluster with one replica.
 *
 * @author Paolo Di Tommaso
 */
class JedisConnectorTest extends Specification {

    @Shared Network network = Network.newNetwork()
    @Shared GenericContainer nodeA
    @Shared GenericContainer nodeB

    def setupSpec() {
        nodeA = clusterNode()
        nodeB = clusterNode()
        nodeA.start()
        nodeB.start()
        // one shard: A owns every slot, B replicates A
        final idA = withNode(ipOf(nodeA)) { it.clusterMyId() }
        withNode(ipOf(nodeA)) { it.clusterAddSlotsRange(0, 16383); it.clusterMeet(ipOf(nodeB), 6379) }
        new PollingConditions(timeout: 30).eventually {
            assert withNode(ipOf(nodeB)) { it.clusterNodes() }.contains(idA)
        }
        withNode(ipOf(nodeB)) { it.clusterReplicate(idA) }
        new PollingConditions(timeout: 30).eventually {
            assert withNode(ipOf(nodeB)) { replicaInSync(it.role()) }
            assert withNode(ipOf(nodeA)) { it.clusterInfo() }.contains('cluster_state:ok')
        }
    }

    def cleanupSpec() {
        nodeA?.stop()
        nodeB?.stop()
        network?.close()
    }

    def 'should follow the primary across a failover'() {
        given: 'a connector seeded with the replica, as a MemoryDB clustercfg record may resolve'
        def connector = new JedisConnector(new HostAndPort(ipOf(nodeB), 6379), clientConfig(), RedisMode.CLUSTER_PRIMARY)
        def pool = new JedisPoolFactory().createRedisPool(connector, 0, 10, 10, false, 2000)

        when: 'writing through the pool'
        pool.resource.withCloseable { it.set('k', 'v1') }

        then: 'connections land on the primary, not on the seed'
        connector.target() == new HostAndPort(ipOf(nodeA), 6379)
        pool.resource.withCloseable { it.get('k') } == 'v1'

        when: 'the replica takes over, leaving an idle pooled connection on the demoted node'
        withNode(ipOf(nodeB)) { it.clusterFailover() }
        new PollingConditions(timeout: 30).eventually {
            assert withNode(ipOf(nodeB)) { JedisConnector.isPrimary(it) }
            assert withNode(ipOf(nodeA)) { !JedisConnector.isPrimary(it) }
        }
        // no waiting for CLUSTER SHARDS to catch up: right after a failover it still lists both nodes as
        // primary, which discovery must see through
        then: 'a dedicated connection, opened while the connector still points at the demoted node, retries onto the new primary'
        connector.connect().withCloseable { JedisConnector.isPrimary(it) }
        connector.target() == new HostAndPort(ipOf(nodeB), 6379)

        when: 'borrowing with an idle connection still open to the demoted node'
        pool.resource.withCloseable { it.set('k', 'v2') }

        then: 'the stale connection is evicted and the pool writes to the new primary'
        pool.resource.withCloseable { it.get('k') } == 'v2'

        cleanup:
        pool?.close()
    }

    def 'should rediscover the primary when the cached one is unreachable and no idle connection is left'() {
        given: 'a pool with no idle connection, whose cached primary is a dead address'
        def connector = new JedisConnector(new HostAndPort(ipOf(nodeA), 6379), clientConfig(), RedisMode.CLUSTER_PRIMARY)
        def pool = new JedisPoolFactory().createRedisPool(connector, 0, 10, 10, false, 2000)
        connector.@primary.set(new HostAndPort('127.0.0.1', 1))

        expect: 'the failed connection invalidates it and the retry reaches the real primary'
        pool.resource.withCloseable { it.set('dead', 'ok') } == 'OK'
        connector.target() in [new HostAndPort(ipOf(nodeA), 6379), new HostAndPort(ipOf(nodeB), 6379)]

        cleanup:
        pool?.close()
    }

    def 'should work for a hardened user and report a missing permission on the probe key'() {
        given:
        [nodeA, nodeB].each { node -> withNode(ipOf(node)) { it.aclSetUser(USER, 'on', '>secret', KEYS, '+@all', '-@dangerous') } }
        def config = DefaultJedisClientConfig.builder().user(USER).password('secret').hostAndPortMapper(clientConfig().hostAndPortMapper).build()
        def connector = new JedisConnector(new HostAndPort(ipOf(nodeA), 6379), config, RedisMode.CLUSTER_PRIMARY)

        when:
        def target = null
        def error = null
        try { target = connector.target() } catch (JedisException e) { error = e }

        then:
        (target != null) == FOUND
        FOUND || error.cause.message.contains('NOPERM')

        where:
        USER        | KEYS        | FOUND
        'hardened'  | '~*'        | true      // +@all -@dangerous: no ROLE, discovery still works
        'no-probe'  | '~app:*'    | false     // no read access to the probe key: surfaced as the cause, not swallowed
    }

    def 'should invalidate the cached primary only if it is still the failed node'() {
        given:
        def connector = new JedisConnector(new HostAndPort('seed', 6379), DefaultJedisClientConfig.builder().build(), RedisMode.CLUSTER_PRIMARY)
        def current = new HostAndPort('10.0.0.2', 6379)
        connector.@primary.set(current)

        when: 'a stale connection to the old primary is evicted after another caller rediscovered'
        connector.invalidate(new HostAndPort('10.0.0.1', 6379))
        then:
        connector.@primary.get() == current

        when: 'the current primary fails'
        connector.invalidate(current)
        then:
        connector.@primary.get() == null
    }

    def 'should list the online primary of a single shard'() {
        expect:
        JedisConnector.primariesOf([shard(node('replica', '10.0.0.2', 6379, null), node('master', '10.0.0.1', 6379, TLS_PORT))], SSL) == [new HostAndPort('10.0.0.1', EXPECTED_PORT)]

        where:
        SSL   | TLS_PORT | EXPECTED_PORT
        false | null     | 6379
        true  | null     | 6379
        true  | 6380     | 6380
    }

    def 'should refuse a cluster with more than one shard'() {
        when:
        JedisConnector.primariesOf([shard(node('master', '10.0.0.1', 6379, null)), shard(node('master', '10.0.0.2', 6379, null))], false)

        then:
        thrown(JedisException)
    }

    // -- helpers

    private GenericContainer clusterNode() {
        new GenericContainer(DockerImageName.parse('redis:7.2-alpine'))
                .withNetwork(network)
                .withExposedPorts(6379)
                .withCommand('redis-server', '--port', '6379', '--cluster-enabled', 'yes', '--cluster-node-timeout', '2000', '--appendonly', 'no')
                .waitingFor(Wait.forLogMessage('.*Ready to accept connections.*\\n', 1))
    }

    private String ipOf(GenericContainer container) {
        container.containerInfo.networkSettings.networks.values().first().ipAddress
    }

    /**
     * Nodes announce their network-internal addresses, which the test JVM cannot reach: map them to
     * the mapped ports, as a client inside the VPC would reach MemoryDB nodes directly.
     */
    private JedisClientConfig clientConfig() {
        final HostAndPortMapper mapper = { HostAndPort hp ->
            final container = [nodeA, nodeB].find { ipOf(it) == hp.host }
            container ? new HostAndPort(container.host, container.getMappedPort(6379)) : hp
        } as HostAndPortMapper
        DefaultJedisClientConfig.builder().hostAndPortMapper(mapper).build()
    }

    private <T> T withNode(String ip, Closure<T> action) {
        new Jedis(new HostAndPort(ip, 6379), clientConfig()).withCloseable(action)
    }

    private static boolean replicaInSync(List<Object> role) {
        role.size() >= 4 && new String(role[0] as byte[]) == 'slave' && new String(role[3] as byte[]) == 'connected'
    }

    private static ClusterShardInfo shard(ClusterShardNodeInfo... nodes) {
        new ClusterShardInfo([(ClusterShardInfo.SLOTS): [[0L, 16383L]], (ClusterShardInfo.NODES): nodes as List])
    }

    private static ClusterShardNodeInfo node(String role, String ip, long port, Long tlsPort) {
        final map = [(ClusterShardNodeInfo.ID): ip, (ClusterShardNodeInfo.ENDPOINT): ip, (ClusterShardNodeInfo.IP): ip,
                     (ClusterShardNodeInfo.PORT): port, (ClusterShardNodeInfo.ROLE): role, (ClusterShardNodeInfo.HEALTH): 'online']
        if (tlsPort != null) {
            map[ClusterShardNodeInfo.TLS_PORT] = tlsPort
        }
        new ClusterShardNodeInfo(map as Map<String, Object>)
    }
}
