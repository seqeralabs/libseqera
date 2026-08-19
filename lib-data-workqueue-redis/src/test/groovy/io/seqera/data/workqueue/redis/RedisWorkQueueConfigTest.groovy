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

package io.seqera.data.workqueue.redis

import java.time.Duration

import spock.lang.Specification

/**
 * Covers the lease tuning settings of {@link RedisWorkQueueConfig}: the renewal period and
 * the max lease age derive from the visibility timeout by default — so the margin math
 * tracks a re-tuned visibility timeout — and can be overridden independently.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class RedisWorkQueueConfigTest extends Specification {

    static class DefaultsConfig implements RedisWorkQueueConfig {
        @Override
        String getDefaultConsumerGroupName() { 'test-group' }
        @Override
        Duration getVisibilityTimeout() { Duration.ofSeconds(60) }
        @Override
        Duration getConsumerWarnTimeout() { Duration.ofSeconds(40) }
    }

    /** Defaults with explicit tuning overrides — null keeps the derived default. */
    static class TuningConfig extends DefaultsConfig {
        Duration renewalPeriod
        Duration leaseAge
        @Override
        Duration getLeaseRenewalPeriod() { renewalPeriod != null ? renewalPeriod : super.getLeaseRenewalPeriod() }
        @Override
        Duration getMaxLeaseAge() { leaseAge != null ? leaseAge : super.getMaxLeaseAge() }
    }

    def 'lease settings should derive from the visibility timeout by default' () {
        given:
        def config = new DefaultsConfig()

        expect: 'renewal period is a quarter of the visibility timeout - two missed ticks tolerated with margin'
        config.getLeaseRenewalPeriod() == Duration.ofSeconds(15)
        and: 'the leak backstop is three visibility timeouts'
        config.getMaxLeaseAge() == Duration.ofMinutes(3)
    }

    def 'lease settings should be overridable independently of the visibility timeout' () {
        given:
        def config = new DefaultsConfig() {
            @Override
            Duration getLeaseRenewalPeriod() { Duration.ofSeconds(5) }
            @Override
            Duration getMaxLeaseAge() { Duration.ofMinutes(10) }
        }

        expect:
        config.getLeaseRenewalPeriodMillis() == 5_000
        config.getMaxLeaseAgeMillis() == 600_000
    }

    def 'a renewal period that cannot protect a lease should fail fast at startup' () {
        given: 'a period >= the visibility timeout: every lease would be claimable before its first renewal'
        def stream = new RedisWorkQueue()
        stream.@config = new DefaultsConfig() {
            @Override
            Duration getLeaseRenewalPeriod() { Duration.ofSeconds(60) }
        }

        when:
        stream.create()

        then:
        thrown(IllegalStateException)
    }

    def 'a non-positive renewal period should fail fast instead of being clamped' () {
        given: 'clamping to 1ms would mean ~1000 renewal pipelines per second against Redis'
        def stream = new RedisWorkQueue()
        stream.@config = new TuningConfig(renewalPeriod: period)

        when:
        stream.create()

        then:
        thrown(IllegalStateException)

        where:
        period << [Duration.ZERO, Duration.ofSeconds(-5)]
    }

    def 'a non-positive max lease age should fail fast instead of pruning everything' () {
        given: 'a zero age would make every unbound lease a "leak" on its first tick'
        def stream = new RedisWorkQueue()
        stream.@config = new TuningConfig(leaseAge: age)

        when:
        stream.create()

        then:
        thrown(IllegalStateException)

        where:
        age << [Duration.ZERO, Duration.ofSeconds(-1)]
    }

}
