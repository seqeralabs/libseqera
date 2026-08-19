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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.data.workqueue.MessageConsumer;
import io.seqera.data.workqueue.MessageLease;
import io.seqera.data.workqueue.WorkQueue;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@link WorkQueue} using Java {@link DelayQueue}
 * as the underlying storage mechanism. This implementation is designed exclusively for
 * development, testing, and local environments.
 *
 * <p><strong>Important:</strong> This implementation should <b>never</b> be used in production
 * environments as it provides no persistence, durability, or distribution capabilities.
 * Messages are stored only in local JVM memory and will be lost on application restart.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li><b>Local Only:</b> Messages exist only within the current JVM instance</li>
 *   <li><b>No Persistence:</b> All messages are lost when the application stops</li>
 *   <li><b>No Distribution:</b> Cannot share messages across multiple application instances</li>
 *   <li><b>Paced Retries:</b> a {@code RETRY} settlement re-queues the message with a
 *       redelivery delay ({@code workqueue.local.retry-delay}, default 1s) — the local analog
 *       of the pacing the visibility timeout provides on Redis. Without it, a consumer that
 *       retries fast (a handler repeatedly declaring RUNNING, or throwing quickly) would
 *       drive a hot loop of continuous re-deliveries in non-Redis deployments</li>
 * </ul>
 *
 * <p>Lease semantics mirror the Redis implementation in-memory: a message whose consumer
 * returned {@link MessageConsumer.Decision#DEFERRED} stays unavailable until its
 * {@link MessageLease} settles — {@code ack()} removes it, {@code retry()} makes it
 * redeliverable after the retry delay. Settlement is idempotent, first call wins.
 *
 * <p>This implementation automatically activates when the 'redis' environment is <b>not</b>
 * active, making it ideal for:
 * <ul>
 *   <li>Local development without Redis infrastructure</li>
 *   <li>Unit testing scenarios</li>
 *   <li>Quick prototyping and experimentation</li>
 * </ul>
 *
 * <p>Each queue is backed by its own {@link ConcurrentHashMap} entry containing
 * a {@link DelayQueue} for thread-safe, availability-aware message handling.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
@Requires(missingBeans = RedisActivator.class)
@Singleton
public class LocalWorkQueue implements WorkQueue<String> {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkQueue.class);

    private final ConcurrentHashMap<String, DelayQueue<DelayedMessage>> delegate = new ConcurrentHashMap<>();

    /**
     * Redelivery delay applied by a {@code RETRY} settlement — the local analog of the
     * visibility-timeout cadence on Redis, deliberately much shorter: local is a dev/test
     * profile where snappy re-polls are a feature; what matters is that the cadence is
     * bounded, not that it matches production.
     */
    @Value("${workqueue.local.retry-delay:1s}")
    private Duration retryDelay = Duration.ofSeconds(1);

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(String queueId) {
        delegate.put(queueId, new DelayQueue<>());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void offer(String queueId, String message) {
        offer(queueId, message, Duration.ZERO);
    }

    private void offer(String queueId, String message, Duration delay) {
        delegate
                .get(queueId)
                .offer(new DelayedMessage(message, delay));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The polled message is naturally "leased" by being out of the queue: an
     * {@code ACK} drops it, a {@code RETRY} — returned, settled via the lease, or caused
     * by a consumer throw — re-queues it with the retry delay, and a {@code DEFERRED}
     * leaves it out of the queue until the lease settles.
     */
    @Override
    public MessageConsumer.Decision consume(String queueId, MessageConsumer<String> consumer) {
        // DelayQueue.poll() only returns a message whose availability delay has expired
        final var delayed = delegate
                .get(queueId)
                .poll();
        if (delayed == null) {
            return null;
        }

        final String message = delayed.message;
        final LocalLease lease = new LocalLease(queueId, message);
        final MessageConsumer.Decision decision;
        try {
            decision = consumer.accept(message, lease);
        }
        catch (Throwable e) {
            // consumer throw settles as RETRY: the message redelivers after the retry delay
            log.debug("Failed to consume message from queue={} - cause: {}", queueId, e.getMessage(), e);
            lease.retry();
            return MessageConsumer.Decision.RETRY;
        }
        settle(lease, decision);
        return decision;
    }

    /**
     * Settle a synchronous decision; first settlement wins, so a consumer that already
     * settled through the lease makes the returned decision a no-op.
     */
    private void settle(LocalLease lease, MessageConsumer.Decision decision) {
        if (decision == null || decision == MessageConsumer.Decision.RETRY) {
            lease.retry();
            return;
        }
        if (decision == MessageConsumer.Decision.ACK) {
            lease.ack();
        }
        // DEFERRED: the consumer's task owns the lease and settles it later
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int length(String queueId) {
        return delegate.get(queueId).size();
    }

    /**
     * A queued message with an availability time: {@link DelayQueue} hands it out only
     * once the delay expires. Fresh offers carry a zero delay (immediately available);
     * retries carry the retry delay. Sequential {@link System#nanoTime()} stamps keep
     * FIFO order among equally-available messages.
     */
    private static final class DelayedMessage implements Delayed {

        private final String message;

        private final long availableAt;

        private DelayedMessage(String message, Duration delay) {
            this.message = message;
            this.availableAt = System.nanoTime() + delay.toNanos();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(availableAt - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof DelayedMessage dm) {
                return Long.compare(availableAt, dm.availableAt);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }

    /**
     * In-memory settlement handle: the message is already out of the queue, so
     * {@code ack()} only marks it settled while {@code retry()} re-queues it with the
     * retry delay.
     */
    private final class LocalLease implements MessageLease {

        private final String queueId;

        private final String message;

        private final AtomicBoolean settled = new AtomicBoolean();

        private LocalLease(String queueId, String message) {
            this.queueId = queueId;
            this.message = message;
        }

        @Override
        public void ack() {
            settled.compareAndSet(false, true);
        }

        @Override
        public void retry() {
            if (settled.compareAndSet(false, true)) {
                offer(queueId, message, retryDelay);
            }
        }

        @Override
        public void retryAfter(Duration delay) {
            if (settled.compareAndSet(false, true)) {
                offer(queueId, message, delay);
            }
        }
    }
}
