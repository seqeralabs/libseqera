/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.util.retry

import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Consumer
import java.util.function.Predicate

import dev.failsafe.Failsafe
import dev.failsafe.FailsafeException
import dev.failsafe.RetryPolicy
import dev.failsafe.RetryPolicyBuilder
import dev.failsafe.event.EventListener
import dev.failsafe.event.ExecutionAttemptedEvent
import dev.failsafe.event.ExecutionCompletedEvent
import dev.failsafe.function.CheckedSupplier
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Implements a retry strategy based on Fail safe
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
@Slf4j
class Retryable<R> {

    interface Config {
        Duration getDelay()
        Duration getMaxDelay()
        int getMaxAttempts()
        double getJitter()
        double getMultiplier()
    }

    private static class ConfigImpl implements Config {
        final Duration delay;
        final Duration maxDelay;
        final int maxAttempts;
        final double jitter;
        final double multiplier;

        private ConfigImpl() {
            this.delay = DEFAULT_DELAY
            this.maxDelay = DEFAULT_MAX_DELAY
            this.maxAttempts = DEFAULT_MAX_ATTEMPTS
            this.jitter = DEFAULT_JITTER
            this.multiplier = DEFAULT_MULTIPLIER
        }

        private ConfigImpl(Config config) {
            this.delay = config.delay ?: DEFAULT_DELAY
            this.maxDelay = config.maxDelay ?: DEFAULT_MAX_DELAY
            this.maxAttempts = config.maxAttempts ?: DEFAULT_MAX_ATTEMPTS
            this.jitter = config.jitter ?: DEFAULT_JITTER
            this.multiplier = config.multiplier ?: DEFAULT_MULTIPLIER
        }
    }

    @Canonical
    static class Event<R> {
        String event
        int attempt
        R result
        Throwable failure

        String toString() {
            "$event[attempt=$attempt; failure=${failure?.message}; result=${result}]"
        }
    }

    static final public Duration DEFAULT_DELAY = Duration.ofMillis(500)
    static final public Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30)
    static final public int DEFAULT_MAX_ATTEMPTS = 5
    static final public double DEFAULT_JITTER = 0.25d
    static final public Predicate<? extends Throwable> DEFAULT_CONDITION = (e -> e instanceof IOException) as Predicate<? extends Throwable>
    static final public double DEFAULT_MULTIPLIER = 2.0

    private Config config

    private Predicate<? extends Throwable> condition

    private Consumer<Event<R>> retryEvent

    private Predicate<R> handleResult

    Retryable<R> withConfig(Config config) {
        this.config = new ConfigImpl(config)
        return this
    }

    Config config() {
        return config
    }

    Retryable<R> retryCondition(Predicate<? extends Throwable> cond) {
        this.condition = cond
        return this
    }

    Retryable<R> retryIf(Predicate<R> predicate) {
        this.handleResult = predicate
        return this
    }

    Retryable<R> onRetry(Consumer<Event<R>> event) {
        this.retryEvent = event
        return this
    }

    protected RetryPolicy retryPolicy() {
        final retry0 = new EventListener<ExecutionAttemptedEvent<R>>() {
            @Override
            void accept(ExecutionAttemptedEvent event) throws Throwable {
                retryEvent?.accept(new Event("Retry", event.attemptCount, event.lastResult, event.lastFailure))
                // close the http response
                if( event.lastResult instanceof HttpResponse<?> ) {
                    closeResponse((HttpResponse<?>) event.lastResult)
                }
            }
        }

        final failure0 = new EventListener<ExecutionCompletedEvent<R>>() {
            @Override
            void accept(ExecutionCompletedEvent event) throws Throwable {
                retryEvent?.accept(new Event("Failure", event.attemptCount, event.result, event.failure))
            }
        }

        final RetryPolicyBuilder<R> policy = RetryPolicy.<R>builder()
                .handleIf(condition ?: DEFAULT_CONDITION)
                .withBackoff(config.delay, config.maxDelay, config.multiplier)
                .withMaxAttempts(config.maxAttempts)
                .withJitter(config.jitter)
                .onRetry(retry0)
                .onFailure(failure0)
        if( handleResult!=null )
            policy.handleResultIf(handleResult)
        return policy.build()
    }

    R apply(CheckedSupplier<R> action) {
        final policy = retryPolicy()
        try {
            return Failsafe.with(policy).get(action)
        } catch (FailsafeException e) {
            throw e.cause
        }
    }

    CompletableFuture<R> applyAsync(CheckedSupplier<R> action, Executor executor) {
        final policy = retryPolicy()
        try {
            return Failsafe
                    .with(policy)
                    .with(executor)
                    .getAsync(action)
        } catch (FailsafeException e) {
            throw e.cause
        }
    }

    static <T> Retryable<T> of(Config config) {
        new Retryable<T>().withConfig(config)
    }

    static <T> Retryable<T> ofDefaults() {
        new Retryable<T>().withConfig(new ConfigImpl())
    }

    static protected void closeResponse(HttpResponse<?> response) {
        log.trace "Closing HttpClient response: $response"
        try {
            // close the httpclient response to prevent leaks
            // https://bugs.openjdk.org/browse/JDK-8308364
            final b0 = response.body()
            if( b0 instanceof Closeable )
                b0.close()
        }
        catch (Throwable e) {
            log.debug "Unexpected error while closing http response - cause: ${e.message}", e
        }
    }
}
