/*
 * Copyright 2024, Seqera Labs
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

package io.seqera.util.retry

import java.time.Duration

import groovy.util.logging.Slf4j
import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class RetryableTest extends Specification {

    def 'should create with defaults' () {
        given:
        def retryable = Retryable.ofDefaults()
        expect:
        retryable.config().delay == Retryable.DEFAULT_DELAY
        retryable.config().maxDelay == Retryable.DEFAULT_MAX_DELAY
        retryable.config().maxAttempts == Retryable.DEFAULT_MAX_ATTEMPTS
        retryable.config().jitter == Retryable.DEFAULT_JITTER
        retryable.config().multiplier == Retryable.DEFAULT_MULTIPLIER
    }

    def 'should create with custom config' () {
        given:
        def config = Mock(Retryable.Config) {
            delay >> Duration.ofSeconds(1)
            maxDelay >> Duration.ofSeconds(2)
            maxAttempts >> 3
            jitter >> 4d
            multiplier >> 5d
        }
        and:
        def retryable = Retryable.of(config)

        expect:
        retryable.config().delay == Duration.ofSeconds(1)
        retryable.config().maxDelay == Duration.ofSeconds(2)
        retryable.config().maxAttempts == 3
        retryable.config().jitter == 4d
        retryable.config().multiplier == 5d
    }

    def 'should retry on predicate' () {
        given:
        def count = 0
        def retries = 0
        and:
        def retryable = Retryable.<Long>ofDefaults()
                .onRetry((event)-> ++retries)
                .retryIf((c) -> c<3)

        when:
        retryable.apply((a)-> ++count)
        then:
        count == 3
        retries == 2
    }

    def 'should retry on condition' () {
        given:
        def count = 0
        def retries = 0
        and:
        def retryable = Retryable.<Long>ofDefaults()
                .onRetry((event)-> ++retries)
                .retryCondition ((Throwable e)-> e instanceof RuntimeException)

        when:
        retryable.apply((a)-> {
            if( ++count < 3 ) throw new RuntimeException("Soft error")
            else throw new IOException("Hard error")
        })
        then:
        thrown(IOException)
        and:
        count == 3
        retries == 2
    }

    def 'should retry execution'  () {
        given:
        def config = Mock(Retryable.Config){
            getDelay() >> Duration.ofSeconds(1)
            getMaxDelay() >> Duration.ofSeconds(10)
            getMaxAttempts() >> 10
            getJitter() >> 0.25
        }
        and:
        int attempts = 0
        def retryable = Retryable.of(config).onRetry { log.info("Attempt ${it.attemptCount}") }
        when:
        def result = retryable.apply {
            if( attempts++<2) throw new IOException("Oops failed!")
            return attempts
        }
        then:
        result == 3
    }

    def 'should throw an exception'  () {
        given:
        def config = Mock(Retryable.Config){
            getDelay() >> Duration.ofSeconds(1)
            getMaxDelay() >> Duration.ofSeconds(10)
            getMaxAttempts() >> 1
            getJitter() >> 0.25
        }
        and:
        def retryable = Retryable.of(config).onRetry { log.info("Attempt ${it.attemptCount}") }
        when:
        retryable.apply(()-> {throw new IOException("Oops failed!")})
        then:
        def e = thrown(IOException)
        e.message == 'Oops failed!'
    }

    def 'should validate config' () {
        given:
        def config = Mock(Retryable.Config){
            getDelay() >> Duration.ofSeconds(1)
            getMaxDelay() >> Duration.ofSeconds(10)
            getMaxAttempts() >> 10
            getJitter() >> 0.25
            getMultiplier() >> 1.5
        }

        when:
        def retry = Retryable.of(config).retryPolicy()
        then:
        retry.getConfig().getDelay() == Duration.ofSeconds(1)
        retry.getConfig().getMaxDelay() == Duration.ofSeconds(10)
        retry.getConfig().getMaxAttempts() == 10
        retry.getConfig().getJitterFactor() == 0.25d
        retry.getConfig().getDelayFactor() == 1.5d
    }
}
