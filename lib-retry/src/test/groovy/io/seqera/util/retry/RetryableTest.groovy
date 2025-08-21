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

package io.seqera.util.retry

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAmount
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

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

    def 'should execute async retry with executor' () {
        given:
        def config = Mock(Retryable.Config){
            getDelay() >> Duration.ofMillis(50)
            getMaxDelay() >> Duration.ofSeconds(1)
            getMaxAttempts() >> 3
            getJitter() >> 0.0
            getMultiplier() >> 1.5
        }
        and:
        int attempts = 0
        def executor = Executors.newCachedThreadPool()
        def retryable = Retryable.of(config).onRetry { log.info("Async attempt ${it.attempt}") }
        
        when:
        CompletableFuture<Integer> future = retryable.applyAsync({
            if( attempts++ < 2) throw new IOException("Async retry failed!")
            return attempts
        }, executor)
        
        then:
        future.get() == 3
        attempts == 3
        
        cleanup:
        executor.shutdown()
    }

    def 'should fail async retry after max attempts' () {
        given:
        def config = Mock(Retryable.Config){
            getDelay() >> Duration.ofMillis(10)
            getMaxDelay() >> Duration.ofMillis(100)
            getMaxAttempts() >> 2
            getJitter() >> 0.0
            getMultiplier() >> 1.5
        }
        and:
        def executor = Executors.newCachedThreadPool()
        def retryable = Retryable.of(config)
        
        when:
        CompletableFuture<String> future = retryable.applyAsync({
            throw new IOException("Always fails!")
        }, executor)
        future.get()
        
        then:
        def e = thrown(Exception)
        e.cause instanceof IOException
        e.cause.message == 'Always fails!'
        
        cleanup:
        executor.shutdown()
    }

    def 'should execute successful async operation without retries' () {
        given:
        def config = Mock(Retryable.Config){
            getDelay() >> Duration.ofMillis(10)
            getMaxDelay() >> Duration.ofSeconds(1)
            getMaxAttempts() >> 3
            getJitter() >> 0.0
            getMultiplier() >> 1.5
        }
        and:
        def executor = Executors.newCachedThreadPool()
        def retryable = Retryable.of(config)
        def retryCount = 0
        retryable.onRetry { retryCount++ }
        
        when:
        CompletableFuture<String> future = retryable.applyAsync({
            return "success"
        }, executor)
        
        then:
        future.get() == "success"
        retryCount == 0
        
        cleanup:
        executor.shutdown()
    }

    def 'should convert delay to duration' () {
        given:
        def config = new Retryable.Config() {
            @Override
            TemporalAmount getDelay() { return Duration.ofSeconds(5) }
            @Override
            TemporalAmount getMaxDelay() { return Duration.ofMinutes(1) }
            @Override
            int getMaxAttempts() { return 3 }
            @Override
            double getJitter() { return 0.0 }
            @Override
            double getMultiplier() { return 1.5 }
        }
        
        expect:
        config.getDelayAsDuration() == Duration.ofSeconds(5)
    }

    def 'should convert temporal amount to duration' () {
        given:
        def temporalAmount = Duration.of(1500, ChronoUnit.MILLIS)
        def config = new Retryable.Config() {
            @Override
            TemporalAmount getDelay() { return temporalAmount }
            @Override
            TemporalAmount getMaxDelay() { return Duration.ofMinutes(1) }
            @Override
            int getMaxAttempts() { return 3 }
            @Override
            double getJitter() { return 0.0 }
            @Override
            double getMultiplier() { return 1.5 }
        }
        
        expect:
        config.getDelayAsDuration() == Duration.ofMillis(1500)
    }

    def 'should handle null delay' () {
        given:
        def config = new Retryable.Config() {
            @Override
            TemporalAmount getDelay() { return null }
            @Override
            TemporalAmount getMaxDelay() { return Duration.ofMinutes(1) }
            @Override
            int getMaxAttempts() { return 3 }
            @Override
            double getJitter() { return 0.0 }
            @Override
            double getMultiplier() { return 1.5 }
        }
        
        expect:
        config.getDelayAsDuration() == null
    }

    def 'should convert max delay to duration' () {
        given:
        def config = new Retryable.Config() {
            @Override
            TemporalAmount getDelay() { return Duration.ofSeconds(1) }
            @Override
            TemporalAmount getMaxDelay() { return Duration.ofMinutes(2) }
            @Override
            int getMaxAttempts() { return 3 }
            @Override
            double getJitter() { return 0.0 }
            @Override
            double getMultiplier() { return 1.5 }
        }
        
        expect:
        config.getMaxDelayAsDuration() == Duration.ofMinutes(2)
    }

    def 'should convert temporal amount max delay to duration' () {
        given:
        def temporalAmount = Duration.of(30000, ChronoUnit.MILLIS)
        def config = new Retryable.Config() {
            @Override
            TemporalAmount getDelay() { return Duration.ofSeconds(1) }
            @Override
            TemporalAmount getMaxDelay() { return temporalAmount }
            @Override
            int getMaxAttempts() { return 3 }
            @Override
            double getJitter() { return 0.0 }
            @Override
            double getMultiplier() { return 1.5 }
        }
        
        expect:
        config.getMaxDelayAsDuration() == Duration.ofSeconds(30)
    }

    def 'should handle null max delay' () {
        given:
        def config = new Retryable.Config() {
            @Override
            TemporalAmount getDelay() { return Duration.ofSeconds(1) }
            @Override
            TemporalAmount getMaxDelay() { return null }
            @Override
            int getMaxAttempts() { return 3 }
            @Override
            double getJitter() { return 0.0 }
            @Override
            double getMultiplier() { return 1.5 }
        }
        
        expect:
        config.getMaxDelayAsDuration() == null
    }

    def 'should create event with all parameters' () {
        given:
        def exception = new IOException("test error")
        def event = new Retryable.Event<>("Retry", 3, "result", exception)

        expect:
        event.getEvent() == "Retry"
        event.getAttempt() == 3
        event.getResult() == "result"
        event.getFailure() == exception
    }

    def 'should create event with null values' () {
        given:
        def event = new Retryable.Event<String>("Success", 1, null, null)

        expect:
        event.getEvent() == "Success"
        event.getAttempt() == 1
        event.getResult() == null
        event.getFailure() == null
    }

    def 'should implement equals correctly' () {
        given:
        def exception1 = new IOException("test")
        def exception2 = new IOException("test")
        def event1 = new Retryable.Event<>("Retry", 2, "result", exception1)
        def event2 = new Retryable.Event<>("Retry", 2, "result", exception1)
        def event3 = new Retryable.Event<>("Failure", 2, "result", exception1)
        def event4 = new Retryable.Event<>("Retry", 3, "result", exception1)
        def event5 = new Retryable.Event<>("Retry", 2, "different", exception1)
        def event6 = new Retryable.Event<>("Retry", 2, "result", exception2)

        expect:
        event1 == event2
        event1 != event3
        event1 != event4
        event1 != event5
        event1 != event6
        event1 != null
        event1 != "not an event"
    }

    def 'should implement hashCode correctly' () {
        given:
        def exception = new IOException("test")
        def event1 = new Retryable.Event<>("Retry", 2, "result", exception)
        def event2 = new Retryable.Event<>("Retry", 2, "result", exception)
        def event3 = new Retryable.Event<>("Failure", 2, "result", exception)

        expect:
        event1.hashCode() == event2.hashCode()
        event1.hashCode() != event3.hashCode()
    }

    def 'should implement toString correctly' () {
        given:
        def exception = new IOException("test error")
        def event1 = new Retryable.Event<>("Retry", 2, "success", exception)
        def event2 = new Retryable.Event<>("Success", 1, "result", null)
        def event3 = new Retryable.Event<>("Failure", 3, null, exception)

        expect:
        event1.toString() == "Retry[attempt=2; failure=test error; result=success]"
        event2.toString() == "Success[attempt=1; failure=null; result=result]"
        event3.toString() == "Failure[attempt=3; failure=test error; result=null]"
    }
}
