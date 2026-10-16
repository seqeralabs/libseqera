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

import java.util.concurrent.ConcurrentHashMap

import spock.lang.Specification
import spock.lang.Timeout

import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.HostAndPortMapper
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisConnectionException

/**
 * Replays the MemoryDB failover of libseqera#148 against two fake nodes. The promoted node's
 * {@code CLUSTER SHARDS} lists the failed primary as an extra shard; what that entry holds was not
 * captured, so each plausible shape is a variant. Discovery does not read the topology, so it must
 * reach the promoted primary whatever the reply says, as soon as that node serves keyed commands.
 *
 * @author Paolo Di Tommaso
 */
@Timeout(60)
class JedisConnectorMemoryDbFailoverTest extends Specification {

    static final String OLD = '10.0.0.1'
    static final String NEW = '10.0.0.2'
    static final List FULL = [0L, 16383L]

    FakeNode old = new FakeNode()
    FakeNode neu = new FakeNode()

    def cleanup() {
        old.close(); neu.close()
    }

    def 'should reach the promoted primary while the reply lists the failed one as an extra shard'() {
        given: 'one shard: OLD primary, NEW replica'
        def shards = [shard(FULL, node(OLD, 'master', 'online'), node(NEW, 'replica', 'online'))]
        old.with { mode = 'primary'; it.shards = shards }
        neu.with { mode = 'replica'; movedTo = OLD; it.shards = shards }
        def connector = connector()
        def pool = new JedisPoolFactory().createRedisPool(connector, 0, 10, 10, false, 2000)
        pool.resource.withCloseable { it.set('k', 'v0') }
        assert connector.target() == new HostAndPort(OLD, 6379)

        when: 'OLD goes down and NEW is promoted, its reply carrying an extra shard'
        old.mode = 'down'
        neu.mode = NEW_MODE
        neu.shards = [shard(OLD_SLOTS, node(OLD, 'master', OLD_HEALTH)), shard(FULL, node(NEW, 'master', 'online'))]

        then:
        writes(pool) == RECOVERS
        !RECOVERS || connector.target() == new HostAndPort(NEW, 6379)

        when: 'the reply is single-shard again'
        neu.mode = 'primary'
        neu.shards = [shard(FULL, node(NEW, 'master', 'online'), node(OLD, 'replica', 'online'))]

        then:
        writes(pool)

        cleanup:
        pool?.close()

        where:
        OLD_SLOTS | OLD_HEALTH | NEW_MODE      | RECOVERS
        []        | 'fail'     | 'primary'     | true      // failed primary listed with no slots
        []        | 'online'   | 'primary'     | true      // ... and still reported online
        FULL      | 'fail'     | 'primary'     | true      // ... and still owning the slots
        []        | 'fail'     | 'clusterdown' | false     // promoted node not serving yet: nothing to reach
    }

    def 'should skip a node answering CLUSTERDOWN and reach the primary through the next one'() {
        given:
        old.mode = 'clusterdown'
        neu.mode = 'primary'

        expect:
        connector().target() == new HostAndPort(NEW, 6379)
    }

    def 'should report what each address answered when none leads to the primary'() {
        given: 'OLD down, NEW promoted but not serving yet'
        old.mode = 'down'
        neu.mode = 'clusterdown'

        when:
        connector().target()

        then:
        def e = thrown(JedisConnectionException)
        e.message.startsWith('Unable to discover the Redis cluster primary via clustercfg:6379 - 10.0.0.1:6379: ')
        e.message.contains('; 10.0.0.2:6379: CLUSTERDOWN Hash slot not served')
    }

    // -- helpers

    /** Seeded with both nodes, the failing one first, as the MemoryDB clustercfg record resolves. */
    private JedisConnector connector() {
        final HostAndPortMapper mapper = { HostAndPort hp ->
            final n = hp.host == OLD ? old : hp.host == NEW ? neu : null
            // a down node refuses new connections
            n == null ? hp : n.mode == 'down' ? new HostAndPort('127.0.0.1', 1) : new HostAndPort('127.0.0.1', n.port)
        } as HostAndPortMapper
        final config = DefaultJedisClientConfig.builder().connectionTimeoutMillis(500).socketTimeoutMillis(500).hostAndPortMapper(mapper).build()
        new JedisConnector(new HostAndPort('clustercfg', 6379), config, RedisMode.CLUSTER_PRIMARY) {
            @Override
            protected List<HostAndPort> seedAddresses() { [new HostAndPort(OLD, 6379), new HostAndPort(NEW, 6379)] }
        }
    }

    /** Whether a write succeeds within 3 s. */
    private static boolean writes(JedisPool pool) {
        final deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            try {
                pool.resource.withCloseable { it.set('k', 'v') }
                return true
            }
            catch (Exception ignored) {
                sleep 50
            }
        }
        return false
    }

    private static List shard(List slots, List... nodes) {
        ['slots', slots, 'nodes', nodes as List]
    }

    private static List node(String ip, String role, String health) {
        ['id', ip, 'port', 6379L, 'ip', ip, 'endpoint', ip, 'role', role, 'replication-offset', 0L, 'health', health]
    }

    /**
     * A minimal RESP2 server playing one cluster node: {@code primary}, {@code replica} (keyed commands
     * get {@code MOVED} to {@link #movedTo}), {@code clusterdown}, or {@code down} (drops connections).
     */
    static class FakeNode implements Closeable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        final Map<String, String> data = new ConcurrentHashMap<>()
        volatile String mode = 'primary'
        volatile String movedTo
        volatile List shards = []

        FakeNode() {
            Thread.startDaemon {
                while (!server.closed) {
                    try { final socket = server.accept(); Thread.startDaemon { serve(socket) } }
                    catch (IOException ignored) { }
                }
            }
        }

        int getPort() { server.localPort }

        void close() { server.close() }

        private void serve(Socket socket) {
            socket.withCloseable {
                final input = new DataInputStream(new BufferedInputStream(socket.inputStream))
                final out = new BufferedOutputStream(socket.outputStream)
                while (true) {
                    final cmd = readCommand(input)
                    if (cmd == null || mode == 'down') return
                    write(out, reply(cmd))
                    out.flush()
                }
            }
        }

        private Object reply(List<String> cmd) {
            final name = cmd[0].toUpperCase()
            if (name in ['CLIENT', 'QUIT']) return new Status('+OK')
            if (name == 'PING') return new Status('+PONG')
            if (name == 'CLUSTER') return mode == 'clusterdown' ? CLUSTERDOWN : shards
            if (mode == 'clusterdown') return CLUSTERDOWN
            if (mode == 'replica') return new Status("-MOVED 1234 ${movedTo}:6379")
            if (name == 'EXISTS') return data.containsKey(cmd[1]) ? 1L : 0L
            if (name == 'SET') { data[cmd[1]] = cmd[2]; return new Status('+OK') }
            return data[cmd[1]]
        }

        static final Status CLUSTERDOWN = new Status('-CLUSTERDOWN Hash slot not served')

        private static List<String> readCommand(DataInputStream input) {
            final header = readLine(input)
            if (header == null) return null
            (0..<(header.substring(1) as int)).collect {
                final bytes = new byte[readLine(input).substring(1) as int]
                input.readFully(bytes)
                readLine(input)
                new String(bytes)
            }
        }

        private static String readLine(DataInputStream input) {
            final line = new StringBuilder()
            int c
            while ((c = input.read()) != -1) {
                if (c == ('\r' as char)) { input.read(); return line.toString() }
                line.append((char) c)
            }
            return null
        }

        private static void write(OutputStream out, Object value) {
            if (value == null) out.write('$-1\r\n'.bytes)
            else if (value instanceof Status) out.write("${value.line}\r\n".bytes)
            else if (value instanceof Long) out.write(":${value}\r\n".bytes)
            else if (value instanceof String) { out.write("\$${value.bytes.length}\r\n".bytes); out.write(value.bytes); out.write('\r\n'.bytes) }
            else { out.write("*${value.size()}\r\n".bytes); value.each { write(out, it) } }
        }
    }

    /** A status ({@code +}) or error ({@code -}) reply line. */
    static class Status {
        final String line
        Status(String line) { this.line = line }
    }
}
