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

/**
 * Side-effect collector passed to a {@link MessageConsumer} when it is
 * processing a single message. The consumer can call {@link #offer} to
 * request additional messages be delivered to other streams <i>atomically</i>
 * with the acknowledgment of the current message — in a Redis stream
 * implementation this means the XACK/XDEL of the current message and all
 * queued XADDs run inside one {@code MULTI/EXEC} block.
 *
 * <p>Typical use: cross-stream hand-off, where a handler finishes its work on
 * stream A and wants the same (or a derived) message to continue being
 * polled on stream B, without an observable duplicate window under crash.</p>
 *
 * <p>If the consumer returns {@code false} the queued offers are discarded —
 * they only materialise when the source message is acknowledged.</p>
 *
 * @param <T> the message type for the source stream; offers must use the
 *            same type so encoding is consistent
 */
public interface TxContext<T> {

    /**
     * Queue an additional message to be atomically appended to the given
     * destination stream when the current message is acknowledged.
     *
     * <p>The destination stream must be served by the same backend (and, in a
     * Redis Cluster deployment, the same slot) as the source stream for the
     * atomicity guarantee to hold.</p>
     *
     * @param dstStreamId the destination stream id
     * @param message     the message to append; must not be null
     */
    void offer(String dstStreamId, T message);
}
