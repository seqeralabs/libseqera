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

package io.seqera.util.redis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
trait BaseRedisTest {

    private static final Logger log = LoggerFactory.getLogger(BaseRedisTest)

    private static GenericContainer redisContainer

    /**
     * Start the Redis server in a static block, to make sure it's ready before Micronaut tests start.
     *
     * Each class implementing a trait receives its own static field (and static block execution).
     * This allows starting a new server for each spec class method implementing the trait and stopping it on the
     * {@link #cleanupSpec()} method (executed for each test class once the spec finishes).
     * This behaviour differs from parent classes and interfaces, where static members belong to the base class.
     * @see <a href="https://groovy-lang.org/objectorientation.html#_static_methods_properties_and_fields"/>
     */
    static {
       this.startRedisServer()
    }

    private static void startRedisServer() {
        log.debug "Starting Redis server"
        redisContainer = new GenericContainer("cr.seqera.io/public/redis:7")
                        .withExposedPorts(6379)
                        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
        redisContainer.start()
        System.setProperty("TOWER_REDIS_URL","redis://${redisContainer.getHost()}:${redisContainer.getMappedPort(6379)}")
        log.debug "Started Redis server"
    }

    def cleanupSpec() {
        this.stopRedisServer()
    }

    private static void stopRedisServer() {
        log.debug "Stopping Redis server"
        redisContainer?.stop()
        log.debug "Stopped Redis server"
    }

    JedisPool getJedisPool() {
        String host = redisContainer.getHost()
        int port = redisContainer.getMappedPort(6379)
        return new JedisPool(new JedisPoolConfig(), host, port)
    }
}
