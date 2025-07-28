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

import groovy.transform.CompileStatic
import groovy.transform.builder.Builder

/**
 * Implements helper class to compute an exponential delay
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Builder(prefix = 'with')
@CompileStatic
class ExponentialAttempt {

    int backOffBase = 2
    int backOffDelay = 250
    Duration maxDelay = Duration.ofMinutes(1)
    int maxAttempts = Integer.MAX_VALUE
    int attempt

    Duration next() {
        delay(++attempt)
    }

    Duration delay() {
        delay(attempt)
    }

    Duration delay(int attempt) {
        final result = Math.min((Math.pow(backOffBase, attempt) as long) * backOffDelay, maxDelay.toMillis())
        return result>0 ? Duration.ofMillis(result) : maxDelay
    }

    int current() { attempt }

    boolean canAttempt() {
        attempt <= maxAttempts
    }

    void reset() {
        attempt=0
    }
}
