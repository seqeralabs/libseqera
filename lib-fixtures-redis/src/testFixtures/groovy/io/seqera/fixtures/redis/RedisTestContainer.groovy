/*
 * Copyright 2025, Seqera Labs
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

package io.seqera.fixtures.redis

import spock.lang.Shared

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
trait RedisTestContainer {

    private static final Logger log = LoggerFactory.getLogger(RedisTestContainer)

    @Shared
    static GenericContainer redisContainer;

    String getRedisHostName(){
        redisContainer.getHost()
    }

    String getRedisPort(){
        redisContainer.getMappedPort(6379).toString()
    }

    def setupSpec() {
        redisContainer = new GenericContainer(DockerImageName.parse("redis:7.0.4-alpine"))
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
        // starting redis
        redisContainer.start()
        log.debug "Started Redis test container"
        // Set Redis host and port as system properties
        // those properties are accessed by the RedisTestFactory class 
        System.setProperty("redis.host", redisContainer.getHost())
        System.setProperty("redis.port", redisContainer.getMappedPort(6379).toString())
    }

    def cleanupSpec(){
        log.debug "Stopping Redis test container"
        redisContainer.stop()
    }
}
