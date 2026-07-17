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

package io.seqera.data.stream

import java.time.Duration

import io.seqera.serde.encode.StringEncodingStrategy

/**
 * A {@link AbstractMessageStream} used by the async-processing tests. It carries a
 * String payload (identity encoding) and exposes the async knobs — concurrency,
 * poll interval, heartbeat interval and max-processing-time — as constructor options
 * so each test can tune them independently.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TunableStream extends AbstractMessageStream<String> {

    private final int workers
    private final Duration pollDelay
    private final Duration hbInterval
    private final Duration maxProcTime

    TunableStream(Map opts = [:], MessageStream<String> target) {
        super(target)
        this.workers = (opts.concurrency ?: 1) as int
        this.pollDelay = (opts.pollInterval ?: Duration.ofSeconds(1)) as Duration
        this.hbInterval = (opts.heartbeatInterval ?: Duration.ofSeconds(20)) as Duration
        this.maxProcTime = (opts.maxProcessingTime ?: Duration.ofMinutes(15)) as Duration
    }

    @Override
    protected StringEncodingStrategy<String> createEncodingStrategy() {
        return new StringEncodingStrategy<String>() {
            @Override
            String encode(String message) { return message }
            @Override
            String decode(String encoded) { return encoded }
        }
    }

    @Override
    protected String name() {
        return 'tunable-stream'
    }

    @Override
    protected Duration pollInterval() {
        return pollDelay
    }

    @Override
    protected int concurrency() {
        return workers
    }

    @Override
    protected Duration heartbeatInterval() {
        return hbInterval
    }

    @Override
    protected Duration maxProcessingTime() {
        return maxProcTime
    }
}
