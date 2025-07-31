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

package io.seqera.data.stream

import groovy.transform.CompileStatic
import io.micronaut.context.DefaultApplicationContext
import io.micronaut.context.condition.Condition
import io.micronaut.context.condition.ConditionContext
import io.micronaut.context.env.Environment

/**
 * Custom condition to determine Redis availability for message stream implementations.
 * 
 * <p>This condition evaluates whether Redis should be used for message streaming based on:</p>
 * <ul>
 *   <li>Presence of {@code redis.uri} property</li>
 *   <li>Active environment profiles containing 'redis'</li>
 * </ul>
 * 
 * <p>Usage with {@code @Requires(condition = RedisAvailabilityCondition.class)}</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class RedisAvailabilityCondition implements Condition {

    @Override
    boolean matches(ConditionContext context) {
        final applicationContext = (context.getBeanContext() as DefaultApplicationContext)
        final applicationEnvironment = applicationContext.environment
        return hasRedisUri(applicationEnvironment) || isRedisEnvironmentActive(applicationEnvironment)
    }

    /**
     * Check if the redis.uri property is configured
     */
    static boolean hasRedisUri(Environment env) {
        return env.getPropertyEntry("redis.uri").isPresent()
    }

    /**
     * Check if 'redis' environment is active
     */
    static boolean isRedisEnvironmentActive(Environment env) {
        return 'redis' in env.getActiveNames()
    }
}
