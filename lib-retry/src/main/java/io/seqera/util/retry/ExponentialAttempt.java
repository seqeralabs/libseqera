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

import java.time.Duration;

/**
 * Implements helper class to compute an exponential delay
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ExponentialAttempt {

    private int backOffBase = 2;
    private int backOffDelay = 250;
    private Duration maxDelay = Duration.ofMinutes(1);
    private int maxAttempts = Integer.MAX_VALUE;
    private int attempt = 0;

    public ExponentialAttempt() {
    }

    public ExponentialAttempt withBackOffBase(int backOffBase) {
        this.backOffBase = backOffBase;
        return this;
    }

    public ExponentialAttempt withBackOffDelay(int backOffDelay) {
        this.backOffDelay = backOffDelay;
        return this;
    }

    public ExponentialAttempt withMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
        return this;
    }

    public ExponentialAttempt withMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        return this;
    }

    public ExponentialAttempt withAttempt(int attempt) {
        this.attempt = attempt;
        return this;
    }

    public int getBackOffBase() {
        return backOffBase;
    }

    public int getBackOffDelay() {
        return backOffDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getAttempt() {
        return attempt;
    }

    public Duration next() {
        return delay(++attempt);
    }

    public Duration delay() {
        return delay(attempt);
    }

    public Duration delay(int attempt) {
        final long result = Math.min((long) (Math.pow(backOffBase, attempt) * backOffDelay), maxDelay.toMillis());
        return result > 0 ? Duration.ofMillis(result) : maxDelay;
    }

    public int current() { 
        return attempt; 
    }

    public boolean canAttempt() {
        return attempt <= maxAttempts;
    }

    public void reset() {
        attempt = 0;
    }
}