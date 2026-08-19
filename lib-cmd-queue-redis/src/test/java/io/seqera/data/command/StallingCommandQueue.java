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
package io.seqera.data.command;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.seqera.data.workqueue.WorkQueue;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A command queue whose dispatcher never quiesces: {@code awaitQuiescent} spends every
 * millisecond it is given and then reports failure — the {@code dispatcherStopped=false}
 * case seen in production (#955). Used to make the drain's budget split observable.
 *
 * <p>Not a Spock mock: {@code @MockBean} wraps the bean in a proxy that cannot implement
 * {@link CommandQueue}'s protected abstract methods, so the replacement is a real subclass
 * installed through the factory below.
 */
class StallingCommandQueue extends CommandQueue {

    private static final Logger log = LoggerFactory.getLogger(StallingCommandQueue.class);

    /**
     * Budget handed to the FIRST {@code awaitQuiescent} call — the one {@code drain()} makes at
     * step 1, which is the split under test. {@code drain()} calls it again through
     * {@code close()} at step 3, and that later call describes the leftover instead.
     */
    static final AtomicLong FIRST_QUIESCE_BUDGET_MILLIS = new AtomicLong(-1);

    /**
     * When set, calls after the first report that the dispatcher has stopped — the slow-but-not-stuck
     * dispatcher, which outlives step 1's capped wait but stops while {@code close()} waits with the
     * leftover at step 3. Left unset, no call ever reports it stopped, which is the stuck dispatcher
     * seen in production (#955).
     */
    static final AtomicBoolean STOPS_DURING_CLOSE = new AtomicBoolean(false);

    /**
     * Only the first call sleeps. Every call reports failure — the dispatcher never stops — but
     * sleeping again inside {@code close()} at step 3 would spend the leftover budget too, making
     * the drain take the full timeout whatever step 1 did and hiding the very difference this
     * exists to expose. The real implementation returns immediately once the thread has stopped.
     */
    private final AtomicBoolean stalled = new AtomicBoolean(false);

    StallingCommandQueue(WorkQueue<String> target) {
        super(target);
    }

    @Override
    protected String name() {
        return "stalling-command-queue";
    }

    @Override
    protected Duration pollInterval() {
        return Duration.ofMillis(100);
    }

    @Override
    public boolean awaitQuiescent(Duration timeout) {
        FIRST_QUIESCE_BUDGET_MILLIS.compareAndSet(-1, timeout.toMillis());
        if (stalled.compareAndSet(false, true)) {
            try {
                Thread.sleep(Math.max(0, timeout.toMillis()));
            }
            catch (InterruptedException e) {
                log.info("Stalling command queue interrupted while spending its quiesce budget", e);
                Thread.currentThread().interrupt();
            }
            return false;
        }
        return STOPS_DURING_CLOSE.get();
    }
}

/**
 * Installs {@link StallingCommandQueue} in place of the default test queue, for the one spec
 * that needs a dispatcher which never stops. Gated on a property so every other spec in this
 * module keeps the normal {@code TestCommandQueue}.
 */
@Factory
@Requires(property = "test.command-queue.stalling", value = "true")
class StallingCommandQueueFactory {

    @Singleton
    @Replaces(bean = CommandQueue.class, factory = TestCommandQueueFactory.class)
    CommandQueue commandQueue(WorkQueue<String> target) {
        return new StallingCommandQueue(target);
    }
}
