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

package io.seqera.data.workqueue;

import java.time.Duration;

import io.seqera.serde.encode.StringEncodingStrategy;

/**
 * Minimal pure-Java {@link AbstractWorkQueue} subclass used by
 * {@code QueueMetricsClassloaderTest}.
 *
 * <p>Pure Java (not Groovy) so the classloader-isolation test does not have to
 * worry about the Groovy runtime resolving auxiliary classes that might
 * inadvertently pull Micrometer back in.</p>
 */
public class TestPlainQueue extends AbstractWorkQueue<String> {

    public TestPlainQueue(WorkQueue<String> target) {
        super(target);
    }

    @Override
    protected StringEncodingStrategy<String> createEncodingStrategy() {
        return new StringEncodingStrategy<String>() {
            @Override public String encode(String message) { return message; }
            @Override public String decode(String encoded) { return encoded; }
        };
    }

    @Override
    protected String name() { return "test-plain-queue"; }

    @Override
    protected Duration pollInterval() { return Duration.ofSeconds(1); }
}
