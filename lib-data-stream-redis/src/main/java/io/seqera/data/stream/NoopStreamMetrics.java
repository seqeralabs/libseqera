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

package io.seqera.data.stream;

import java.util.function.IntSupplier;

/**
 * No-op {@link StreamMetrics} used when no {@code MeterRegistry} is supplied to
 * {@link AbstractMessageStream}. References no Micrometer types in its bytecode,
 * so the library remains usable on a classpath without {@code micrometer-core}.
 */
final class NoopStreamMetrics implements StreamMetrics {

    static final StreamMetrics INSTANCE = new NoopStreamMetrics();

    private NoopStreamMetrics() {}

    @Override
    public void bindBacklog(String streamId, IntSupplier lengthSupplier) {
        // no-op
    }

    @Override
    public long startSample() {
        return 0L;
    }

    @Override
    public void recordOutcome(long startNanos, String streamId, Outcome outcome) {
        // no-op
    }
}
