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

import io.micronaut.context.ApplicationContext
import io.micronaut.context.condition.ConditionContext
import io.micronaut.context.env.Environment
import spock.lang.Specification

/**
 * Test RedisAvailabilityCondition functionality
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisAvailabilityConditionTest extends Specification {

    def 'should return true when redis.uri property is set'() {
        given:
        def condition = new RedisAvailabilityCondition()
        def context = createMockContext(true, false)

        when:
        def result = condition.matches(context)

        then:
        result
    }

    def 'should return true when redis environment is active'() {
        given:
        def condition = new RedisAvailabilityCondition()
        def context = createMockContext(false, true)

        when:
        def result = condition.matches(context)

        then:
        result
    }

    def 'should return true when both redis.uri and redis environment are present'() {
        given:
        def condition = new RedisAvailabilityCondition()
        def context = createMockContext(true, true)

        when:
        def result = condition.matches(context)

        then:
        result
    }

    def 'should return false when neither redis.uri nor redis environment are present'() {
        given:
        def condition = new RedisAvailabilityCondition()
        def context = createMockContext(false, false)

        when:
        def result = condition.matches(context)

        then:
        !result
    }

    def 'should test hasRedisUri static method directly'() {
        expect:
        RedisAvailabilityCondition.hasRedisUri(createMockEnvironment(true, false))
        !RedisAvailabilityCondition.hasRedisUri(createMockEnvironment(false, false))
    }

    def 'should test isRedisEnvironmentActive static method directly'() {
        expect:
        RedisAvailabilityCondition.isRedisEnvironmentActive(createMockEnvironment(false, true))
        !RedisAvailabilityCondition.isRedisEnvironmentActive(createMockEnvironment(false, false))
    }

    private ConditionContext createMockContext(boolean hasRedisUri, boolean hasRedisEnv) {
        def env = createMockEnvironment(hasRedisUri, hasRedisEnv)
        def appContext = Mock(ApplicationContext)
        def context = Mock(ConditionContext)
        
        appContext.getEnvironment() >> env
        context.getBeanContext() >> appContext
        
        return context
    }
    
    private Environment createMockEnvironment(boolean hasRedisUri, boolean hasRedisEnv) {
        def env = Mock(Environment)
        env.getPropertyEntry("redis.uri") >> (hasRedisUri ? Optional.of("redis://localhost:6379") : Optional.empty())
        env.getActiveNames() >> (hasRedisEnv ? ['redis'] : ['test'])
        return env
    }
}
