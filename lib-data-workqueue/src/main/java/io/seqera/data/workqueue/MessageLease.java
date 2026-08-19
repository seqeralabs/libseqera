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
import java.util.function.BooleanSupplier;

/**
 * Handle to settle a message whose consumer returned
 * {@link MessageConsumer.Decision#DEFERRED} — the entry stays leased (owned by this
 * consumer and heartbeated where the underlying queue supports it) until one of the
 * two settlement methods is invoked.
 *
 * <p>Settlement contract:
 * <ul>
 *   <li><strong>Idempotent, first call wins:</strong> the first {@link #ack()} or
 *       {@link #retry()} settles the lease; every later call on either method is a
 *       no-op.</li>
 *   <li><strong>Callable from any thread:</strong> the task that owns the lease may
 *       settle it from a thread other than the one that consumed the message.</li>
 *   <li><strong>A lease must never outlive its task:</strong> callers are expected to
 *       settle on every exit path (a {@code finally}-guarded {@link #retry()} composes
 *       with a happy-path {@link #ack()} thanks to idempotence).</li>
 * </ul>
 *
 * @author Paolo Di Tommaso &lt;paolo.ditommaso@gmail.com&gt;
 * @see MessageConsumer.Decision#DEFERRED
 */
public interface MessageLease {

    /**
     * Settle the message as processed: stop renewing the lease, then acknowledge and
     * remove the entry from the queue (best-effort — a failed acknowledgment degrades
     * to a redelivery that acknowledges on the caller's terminal check).
     */
    void ack();

    /**
     * Settle the message as not processed: stop renewing the lease only. The entry
     * stays pending and is redelivered on the queue's claim cadence — no further
     * network call is made; stopping renewal is the release.
     */
    void retry();

    /**
     * Settle the message as not processed, with a floor on its redelivery: the entry
     * is redelivered no earlier than {@code delay} from now. Queues with lease
     * renewal keep the entry leased — never stalled — until {@code delay} minus the
     * claim cadence has elapsed, then release it to the normal redelivery clock; a
     * delay at or below the claim cadence degrades to a plain {@link #retry()}.
     *
     * <p>Exists to pace re-polls independently of the failure-detection clock: without
     * it, shortening the visibility timeout for faster crash detection silently
     * multiplies the polling load on every dependency the re-polls touch.
     *
     * @param delay the earliest redelivery, measured from now
     */
    void retryAfter(Duration delay);

    /**
     * Bind a liveness probe for the task that owns this lease. Queues that apply a
     * lease-age backstop (dropping leases whose settlement path appears to have never
     * run) consult the probe before pruning: a lease whose owner is provably alive is
     * never age-pruned, so a legitimately slow task keeps its lease for as long as it
     * actually runs. Without a bound probe the age backstop applies unconditionally.
     *
     * <p>The probe must be cheap, thread-safe and non-throwing — typically
     * {@code () -> !task.isDone()} on the {@link java.util.concurrent.Future} of the
     * owning task. The default implementation is a no-op, for queues without lease
     * renewal.
     *
     * @param alive returns {@code true} while the owning task is still running
     */
    default void bindLiveness(BooleanSupplier alive) {
    }

}
