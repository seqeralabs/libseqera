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

package io.seqera.data.stream.metrics;

/**
 * Outcome of a single consume cycle. Surfaces as the {@code outcome} tag on the
 * {@code seqera.stream.messages} counter and {@code seqera.stream.processing} timer.
 */
public enum Outcome {
    /** Consumer.accept returned true; message was acknowledged and removed. */
    PROCESSED("processed"),
    /** Consumer.accept returned false; message remains available for redelivery. */
    ACTIVE("active"),
    /** Exception escaped the consumer or the underlying stream implementation. */
    ERRORED("errored"),
    /** Poll found no message available. Not counted or timed. */
    EMPTY("empty");

    private final String tag;

    Outcome(String tag) { this.tag = tag; }

    public String tag() { return tag; }
}
