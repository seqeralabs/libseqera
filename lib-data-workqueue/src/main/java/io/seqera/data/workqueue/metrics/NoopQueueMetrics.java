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

package io.seqera.data.workqueue.metrics;

import java.util.function.IntSupplier;

/**
 * No-op {@link QueueMetrics}. Used when no metrics are desired. References no
 * Micrometer types, so the library remains usable on a classpath without
 * {@code micrometer-core}.
 */
public final class NoopQueueMetrics implements QueueMetrics {

    public static final QueueMetrics INSTANCE = new NoopQueueMetrics();

    private NoopQueueMetrics() {}

    @Override public void bindBacklog(String queueId, IntSupplier lengthSupplier) { }
    @Override public long startSample() { return 0L; }
    @Override public void recordOutcome(long startNanos, String queueId, Outcome outcome) { }
}
