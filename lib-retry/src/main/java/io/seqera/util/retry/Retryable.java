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

package io.seqera.util.retry;

import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.RetryPolicy;
import dev.failsafe.RetryPolicyBuilder;
import dev.failsafe.event.EventListener;
import dev.failsafe.event.ExecutionAttemptedEvent;
import dev.failsafe.event.ExecutionCompletedEvent;
import dev.failsafe.function.CheckedSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements a retry strategy based on Fail safe
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class Retryable<R> {

    private static final Logger log = LoggerFactory.getLogger(Retryable.class);

    /**
     * Configuration interface for retry policy settings.
     * Defines the parameters that control the retry behavior including delays,
     * maximum attempts, jitter, and backoff multiplier.
     */
    public interface Config {
        
        /**
         * @return The initial delay between retry attempts
         */
        TemporalAmount getDelay();

        /**
         * Converts the delay to a {@link Duration} object.
         * If the delay is already a Duration, returns it directly.
         * Otherwise, converts using milliseconds as the unit.
         *
         * @return The delay as a Duration object, or null if no delay is configured
         */
        default Duration getDelayAsDuration() {
            final var amount = getDelay();
            if( amount==null )
                return null;
            if( amount instanceof Duration )
                return (Duration) amount;
            return Duration.ofMillis(amount.get(ChronoUnit.MILLIS));
        }

        /**
         * @return The maximum delay allowed between retry attempts
         */
        TemporalAmount getMaxDelay();

        /**
         * Converts the maximum delay to a {@link Duration} object.
         * If the maximum delay is already a Duration, returns it directly.
         * Otherwise, converts using milliseconds as the unit.
         *
         * @return The maximum delay as a Duration object, or null if no maximum delay is configured
         */
        default Duration getMaxDelayAsDuration() {
            final var amount = getMaxDelay();
            if( amount==null )
                return null;
            if( amount instanceof Duration )
                return (Duration) amount;
            return Duration.ofMillis(amount.get(ChronoUnit.MILLIS));
        }

        /**
         * @return The maximum number of retry attempts allowed
         */
        int getMaxAttempts();
        
        /**
         * @return The jitter factor to add randomness to delay calculations (0.0 to 1.0)
         */
        double getJitter();
        
        /**
         * @return The multiplier used for exponential backoff delay calculations
         */
        double getMultiplier();
    }

    private static class ConfigImpl implements Config {
        private final TemporalAmount delay;
        private final TemporalAmount maxDelay;
        private final int maxAttempts;
        private final double jitter;
        private final double multiplier;

        private ConfigImpl() {
            this.delay = DEFAULT_DELAY;
            this.maxDelay = DEFAULT_MAX_DELAY;
            this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
            this.jitter = DEFAULT_JITTER;
            this.multiplier = DEFAULT_MULTIPLIER;
        }

        private ConfigImpl(Config config) {
            this.delay = config.getDelay() != null ? config.getDelay() : DEFAULT_DELAY;
            this.maxDelay = config.getMaxDelay() != null ? config.getMaxDelay() : DEFAULT_MAX_DELAY;
            this.maxAttempts = config.getMaxAttempts() != 0 ? config.getMaxAttempts() : DEFAULT_MAX_ATTEMPTS;
            this.jitter = config.getJitter() != 0.0 ? config.getJitter() : DEFAULT_JITTER;
            this.multiplier = config.getMultiplier() != 0.0 ? config.getMultiplier() : DEFAULT_MULTIPLIER;
        }

        @Override
        public TemporalAmount getDelay() {
            return delay;
        }

        @Override
        public TemporalAmount getMaxDelay() {
            return maxDelay;
        }

        @Override
        public int getMaxAttempts() {
            return maxAttempts;
        }

        @Override
        public double getJitter() {
            return jitter;
        }

        @Override
        public double getMultiplier() {
            return multiplier;
        }
    }

    /**
     * Represents a retry event containing information about the execution attempt.
     * This class captures the details of each retry attempt including the attempt number,
     * result, and any failure that occurred.
     *
     * @param <R> The type of the result returned by the retried operation
     */
    public static class Event<R> {
        public final String event;
        public final int attempt;
        public final R result;
        public final Throwable failure;

        /**
         * Creates a new retry event with the specified parameters.
         *
         * @param event The type of event (e.g., "Retry", "Failure")
         * @param attempt The attempt number (1-based)
         * @param result The result of the execution attempt, if successful
         * @param failure The exception that caused the failure, if any
         */
        public Event(String event, int attempt, R result, Throwable failure) {
            this.event = event;
            this.attempt = attempt;
            this.result = result;
            this.failure = failure;
        }

        /**
         * Gets the event type.
         *
         * @return The event type string
         */
        public String getEvent() {
            return event;
        }

        /**
         * Gets the attempt number for this event.
         *
         * @return The 1-based attempt number
         */
        public int getAttempt() {
            return attempt;
        }

        /**
         * Gets the result of the execution attempt.
         *
         * @return The result if the attempt was successful, null otherwise
         */
        public R getResult() {
            return result;
        }

        /**
         * Gets the exception that caused the failure.
         *
         * @return The failure exception, or null if the attempt was successful
         */
        public Throwable getFailure() {
            return failure;
        }

        @Override
        public String toString() {
            return String.format("%s[attempt=%d; failure=%s; result=%s]", 
                event, 
                attempt, 
                failure != null ? failure.getMessage() : null, 
                result);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Event<?> event1 = (Event<?>) o;
            return attempt == event1.attempt &&
                   Objects.equals(event, event1.event) &&
                   Objects.equals(result, event1.result) &&
                   Objects.equals(failure, event1.failure);
        }

        @Override
        public int hashCode() {
            return Objects.hash(event, attempt, result, failure);
        }
    }

    public static final Duration DEFAULT_DELAY = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    public static final double DEFAULT_JITTER = 0.25d;
    public static final Predicate<? extends Throwable> DEFAULT_CONDITION = e -> e instanceof IOException;
    public static final double DEFAULT_MULTIPLIER = 2.0;

    private Config config;
    private Predicate<? extends Throwable> condition;
    private Consumer<Event<R>> retryEvent;
    private Predicate<R> handleResult;

    public Retryable<R> withConfig(Config config) {
        this.config = new ConfigImpl(config);
        return this;
    }

    public Config config() {
        return config;
    }

    public Retryable<R> retryCondition(Predicate<? extends Throwable> cond) {
        this.condition = cond;
        return this;
    }

    public Retryable<R> retryIf(Predicate<R> predicate) {
        this.handleResult = predicate;
        return this;
    }

    public Retryable<R> onRetry(Consumer<Event<R>> event) {
        this.retryEvent = event;
        return this;
    }

    protected RetryPolicy retryPolicy() {
        final EventListener<ExecutionAttemptedEvent<R>> retry0 = new EventListener<ExecutionAttemptedEvent<R>>() {
            @Override
            public void accept(ExecutionAttemptedEvent<R> event) throws Throwable {
                if (retryEvent != null) {
                    retryEvent.accept(new Event<>("Retry", event.getAttemptCount(), event.getLastResult(), event.getLastFailure()));
                }
                // close the http response
                if (event.getLastResult() instanceof HttpResponse<?>) {
                    closeResponse((HttpResponse<?>) event.getLastResult());
                }
            }
        };

        final EventListener<ExecutionCompletedEvent<R>> failure0 = new EventListener<ExecutionCompletedEvent<R>>() {
            @Override
            public void accept(ExecutionCompletedEvent<R> event) throws Throwable {
                if (retryEvent != null) {
                    retryEvent.accept(new Event<>("Failure", event.getAttemptCount(), event.getResult(), event.getFailure()));
                }
            }
        };

        final RetryPolicyBuilder<R> policy = RetryPolicy.<R>builder()
                .handleIf(condition != null ? condition : DEFAULT_CONDITION)
                .withBackoff(config.getDelayAsDuration(), config.getMaxDelayAsDuration(), config.getMultiplier())
                .withMaxAttempts(config.getMaxAttempts())
                .withJitter(config.getJitter())
                .onRetry(retry0)
                .onFailure(failure0);
        
        if (handleResult != null) {
            policy.handleResultIf(handleResult);
        }
        return policy.build();
    }

    public R apply(CheckedSupplier<R> action) {
        final RetryPolicy policy = retryPolicy();
        try {
            return (R) Failsafe.with(policy).get(action);
        } catch (FailsafeException e) {
            sneakyThrow(e.getCause());
            return null; // This will never be reached due to sneakyThrow
        }
    }
    
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    /**
     * Executes the given action asynchronously with retry logic using the specified executor.
     * The retry policy configured for this instance will be applied to handle failures.
     *
     * @param action The action to execute, which may throw checked exceptions
     * @param executor The executor to use for asynchronous execution
     * @return A CompletableFuture containing the result of the successful execution
     * @throws RuntimeException If the action fails after all retry attempts are exhausted,
     *         the original exception will be re-thrown as a runtime exception
     */
    public CompletableFuture<R> applyAsync(CheckedSupplier<R> action, Executor executor) {
        final RetryPolicy policy = retryPolicy();
        try {
            return (CompletableFuture<R>) Failsafe
                    .with(policy)
                    .with(executor)
                    .getAsync(action);
        } catch (FailsafeException e) {
            sneakyThrow(e.getCause());
            return null; // This will never be reached due to sneakyThrow
        }
    }

    public static <T> Retryable<T> of(Config config) {
        return new Retryable<T>().withConfig(config);
    }

    public static <T> Retryable<T> ofDefaults() {
        return new Retryable<T>().withConfig(new ConfigImpl());
    }

    protected static void closeResponse(HttpResponse<?> response) {
        log.trace("Closing HttpClient response: {}", response);
        try {
            // close the httpclient response to prevent leaks
            // https://bugs.openjdk.org/browse/JDK-8308364
            final Object body = response.body();
            if (body instanceof Closeable) {
                ((Closeable) body).close();
            }
        } catch (Throwable e) {
            log.debug("Unexpected error while closing http response - cause: {}", e.getMessage(), e);
        }
    }
}
