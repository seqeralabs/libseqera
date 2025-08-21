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


import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ExponentialAttemptTest extends Specification {

    @Unroll
    def 'should compute delay' () {
        expect:
        new ExponentialAttempt()
                .withBackOffBase(BACKOFF)
                .withBackOffDelay(DELAY)
                .withMaxDelay(MAX)
                .delay(ATTEMPT) == Duration.ofMillis(EXPECTED)

        where:
        ATTEMPT | BACKOFF   | DELAY     | MAX                       | EXPECTED
        0       | 3         | 250       | Duration.ofSeconds(30)    | 250
        1       | 3         | 250       | Duration.ofSeconds(30)    | 750
        2       | 3         | 250       | Duration.ofSeconds(30)    | 2250
        3       | 3         | 250       | Duration.ofSeconds(30)    | 6750
        10      | 3         | 250       | Duration.ofSeconds(30)    | 30_000
        100     | 3         | 250       | Duration.ofSeconds(30)    | 30_000
        1000    | 3         | 250       | Duration.ofSeconds(30)    | 30_000
        10000   | 3         | 250       | Duration.ofSeconds(30)    | 30_000
    }


}
