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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import io.micronaut.scheduling.TaskExecutors;
import io.seqera.data.command.store.CommandStateStore;
import io.seqera.data.workqueue.MessageConsumer;
import io.seqera.data.workqueue.MessageConsumer.Decision;
import io.seqera.data.workqueue.MessageLease;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the command service.
 * Handles queue consumption and command execution with proper multi-replica support.
 *
 * <p>Processing flow (task-settled delivery — every handler invocation runs on the
 * blocking executor and settles its own message lease when it finishes):
 * <ul>
 *   <li>Stale delivery (state missing or already terminal) → settle {@code ACK}</li>
 *   <li>No handler registered → record FAILED, settle {@code ACK} (or {@code RETRY}
 *       when the write refuses)</li>
 *   <li>Otherwise → submit the handler invocation to the blocking executor and return
 *       {@code DEFERRED}: the task owns the lease, which stays renewed for as long as
 *       the task runs, so a slow handler's entry never goes stalled mid-flight</li>
 *   <li>The task routes explicitly on the state: PROCESSING → checkStatus(),
 *       PENDING → execute(), a terminal snapshot → stale, acked. PROCESSING only
 *       ever means "the handler returned PROCESSING" — never the queue's impatience</li>
 *   <li>...unless a shutdown was signalled ({@code drain()} or {@code stop()}) before the task
 *       reached that routing, in which case it does not route at all: nothing has been mutated, so
 *       the delivery is settled as a retry rather than starting work that is not guaranteed to
 *       finish inside the shutdown budget</li>
 *   <li>Handler result PROCESSING → record PROCESSING (first time only), settle the lease as
 *       a delayed retry: the next checkStatus() poll runs on {@code checkStatusInterval},
 *       decoupled from the visibility timeout (which paces crash detection and error
 *       retries)</li>
 *   <li>Terminal result → record it, settle the lease as ack; a refused write means a
 *       cancel won underneath — settle as retry, the redelivery acks on the terminal
 *       check</li>
 *   <li>If the handler throws → settle as retry; the state is unchanged, so the
 *       redelivery re-executes a PENDING command or re-polls a PROCESSING one. A throw
 *       is treated as transient, never as a terminal failure (deciding permanent
 *       failure is the domain layer's job, see seqeralabs/sched#712)</li>
 *   <li>Admission cap: the dispatcher stops claiming while
 *       {@code maxConcurrency} handler executions are in flight
 *       ({@link MessageConsumer#ready()})</li>
 * </ul>
 */
@Singleton
public class CommandServiceImpl implements CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandServiceImpl.class);

    @Inject
    private CommandConfig config;

    @Inject
    private CommandStateStore store;

    @Inject
    private CommandQueue queue;

    @Inject
    @Named(TaskExecutors.BLOCKING)
    private ExecutorService executor;

    private final Map<String, CommandRegistration<?, ?>> handlers = new ConcurrentHashMap<>();

    private volatile boolean started = false;

    /**
     * Whether this service is shutting down: set by {@link #drain(Duration)} and {@link #stop()},
     * cleared by {@link #start()} so a restarted service accepts work again.
     *
     * <p>Distinct from {@code !started}, which only says the dispatcher stopped claiming. This says
     * the shutdown is under way, and is what a handler task consults to decide whether starting its
     * work is still worth doing.
     */
    private volatile boolean draining = false;

    /**
     * Handler invocations submitted to {@link #executor} that have not returned yet — both
     * execute() calls and checkStatus() polls, which run as tasks alike — each naming the command
     * it is running.
     *
     * <p>One structure on purpose: it is both the count a drain waits on and the identities a
     * shutdown reports. A separate counter alongside it would mean two things to keep in step at
     * three mutation sites, and a window where the count and the names disagree — which is the one
     * thing a shutdown report must not do.
     *
     * <p>Deliberately not derived from {@link #executor}: that pool is shared and
     * container-managed, so its queue says nothing about this service. It backs
     * {@link #activeCommands()}, the {@link #drain(Duration)} wait, the admission cap
     * ({@code ready()}) that stops the dispatcher from claiming while
     * {@code config.maxConcurrency()} tasks are in flight, and the in-flight report.
     *
     * <p>Keyed by {@link #inflightSeq} rather than by command id, which matters more than it looks:
     * two invocations of one command id in flight at once (not expected under the message lease,
     * but not structurally impossible) must not collapse into a single entry — that would
     * under-count the admission cap and the drain, not merely lose a name.
     *
     * <p>The one thing given up is exactness: {@code size()} on a concurrent map is an estimate,
     * not a linearizable count. It costs nothing where it is read. The admission decision is
     * already a heuristic at an instant — work starts and finishes around it — and the only thread
     * that inserts is the dispatcher that reads it, so it always observes its own insert; the only
     * staleness comes from other threads' removals, which makes the count read HIGH and the cap
     * admit fewer rather than more. The drain's wait converges as soon as the last task removes its
     * entry, and is bounded by a deadline regardless.
     */
    private final Map<Long, String> inflight = new ConcurrentHashMap<>();

    /**
     * Source of the {@link #inflight} keys: monotonic, never reused for the life of the
     * JVM, so a key cannot be recycled onto a later invocation while an earlier one still holds it.
     */
    private final AtomicLong inflightSeq = new AtomicLong();

    /**
     * Granularity at which {@link #drain(Duration)} re-checks {@link #inflight}.
     */
    private static final long DRAIN_POLL_MILLIS = 50;

    @Override
    public void start() {
        // Cleared before the guard, mirroring how stop()/drain() set it before theirs: starting is
        // the one operation that means "accept work", so it clears the shutdown signal
        // unconditionally. A service started again after a stop()/drain() must route deliveries to
        // handlers, not keep refusing them for the life of the JVM.
        draining = false;
        if (started) {
            log.debug("Command service already started");
            return;
        }
        started = true;
        queue.addConsumer(new MessageConsumer<>() {
            @Override
            public Decision accept(CommandMsg msg, MessageLease lease) {
                return processCommand(msg, lease);
            }

            @Override
            public boolean ready() {
                // admission cap: stop claiming while maxConcurrency tasks are in flight
                return inflight.size() < config.maxConcurrency();
            }
        });
        log.info("Command service started - consuming commands");
    }

    @Override
    public void stop() {
        // Signalled before the started guard, so the signal does not depend on stop() having
        // anything left to do. In the shipped wiring that is symmetry rather than a fix — every path
        // that clears `started` sets this first — but it keeps the invariant local to each entry
        // point instead of resting on the order of an earlier call.
        draining = true;
        if (!started) {
            return;
        }
        started = false;
        queue.close();
        log.info("Command service stopped");
    }

    @Override
    public boolean drain(Duration timeout) {
        // Signal the shutdown first, before anything is awaited: from here on a handler task that
        // has not started its work refuses to start it (see runCommand), which is what lets the
        // waits below finish in the time they were given instead of outlasting them.
        draining = true;
        if (!started) {
            return activeCommands() == 0;
        }
        started = false;
        final long deadline = System.currentTimeMillis() + Math.max(0, timeout.toMillis());

        // 1. Stop claiming new commands and let the dispatcher finish the message it holds. This
        //    does not release the queue, so an in-progress handler can still acknowledge. Bounded
        //    by a fraction of the budget rather than all of it, so step 2 always gets a slice —
        //    see quiesceBudget().
        final boolean quiesced = queue.awaitQuiescent(quiesceBudget(timeout));

        // 2. Wait for handler tasks still running on the executor — execute() calls and
        //    checkStatus() polls alike. These are the ones that matter: they are mid-flight
        //    against the database, and letting them finish here (and settle their leases) is
        //    the whole point of draining before the context tears down.
        while (!inflight.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(DRAIN_POLL_MILLIS, Math.max(1, deadline - System.currentTimeMillis())));
            }
            catch (InterruptedException e) {
                log.info("Command service drain interrupted - giving up the in-flight wait early", e);
                Thread.currentThread().interrupt();
                break;
            }
        }

        // ONE read, taken HERE — at the deadline, before the queue is released below — and used for
        // everything after it: the outcome, the count and the names. Two reads could describe two
        // different instants, and the one that matters is this one. Reading again after close() would
        // be worse than untidy: close() can take what is left of the budget, so a task that outlived
        // the deadline and finished during it would make the later read empty, and the caller would be
        // told the drain succeeded. Nor can the caller read this for itself afterwards — same race,
        // one stack frame further out.
        final List<String> remaining = activeCommandDetails();
        // 3. Release the queue. Done last so steps 1-2 ran with every collaborator still usable,
        //    and bounded by what is left of OUR budget: close() would otherwise start a second,
        //    independent timer of its own, so a dispatcher that never quiesced could push this
        //    method well past `timeout`. Overrunning matters because the caller's budget is
        //    typically a container's graceful-shutdown grace period, and exceeding it means being
        //    hard-stopped mid-drain — the opposite of what draining is for.
        queue.close(Duration.ofMillis(Math.max(0, deadline - System.currentTimeMillis())));

        // Re-probe AFTER close(): step 1's wait is capped at a fraction of the budget, but close()
        // just waited again with the leftover, so a dispatcher that outlived its quiesce budget may
        // have stopped by now — slow, not stuck, with nothing lost. Unlike the in-flight register
        // above, re-reading this cannot lie: a stopped dispatcher stays stopped, and awaitQuiescent
        // with a zero budget is a state probe, not another wait.
        final boolean stopped = quiesced || queue.awaitQuiescent(Duration.ZERO);
        if (!quiesced && stopped) {
            log.warn("Command dispatcher exceeded its quiesce budget ({}) but stopped within the drain budget ({})", quiesceBudget(timeout), timeout);
        }
        if (!stopped || !remaining.isEmpty()) {
            // Report WHAT was still running, not just how much. A drain that expires with work in
            // flight is an expected shape — a control-plane call can be given a budget longer than
            // this whole drain — and only the ids and types tell that apart from something
            // unexpected. Both values were read at the deadline, so they describe one instant.
            log.warn("Command service drain incomplete - dispatcherStopped={}, activeCommands={}, inFlight={}, timeout={}",
                    stopped, remaining.size(), remaining, timeout);
            return false;
        }
        log.info("Command service drained - no command left in flight");
        return true;
    }

    /**
     * How much of the drain budget step 1 may spend waiting for the dispatcher to quiesce.
     *
     * <p>Bounded so step 2 always gets a slice. Handed the whole budget — as it was — a dispatcher
     * that never quiesces consumes all of it, and because the deadline is taken before step 1 the
     * in-flight wait then runs <strong>zero</strong> iterations: handler tasks mid-flight against
     * the database get no grace at all, which is the one thing the drain exists to provide (#888).
     * That is the signature of both incomplete drains observed in production, each reporting
     * {@code dispatcherStopped=false} (#955).
     *
     * <p>A quarter, derived from the caller's budget the way the lease renewal period derives from
     * the visibility timeout, so re-tuning {@code drain-timeout} scales both halves together and
     * there is no second dial to keep in step with the first.
     *
     * <p>Generous by construction rather than by guess: the dispatcher's own work between two
     * loop-head checks is a {@code findById} and a dispatch decision — every handler invocation
     * runs on the executor, and since #964 a delivery claimed after the shutdown began is not
     * routed at all. Whole drains measure 13-52ms in production, so a quarter of a 20s budget is
     * two orders of magnitude of headroom for a step that should never need seconds.
     */
    private static Duration quiesceBudget(Duration timeout) {
        return timeout.dividedBy(4);
    }

    @Override
    public int activeCommands() {
        return inflight.size();
    }

    /**
     * Name the invocations currently in flight, one entry per invocation, as
     * {@code <commandId>(<type>)}. Deliberately NOT on {@link CommandService}: the only consumer is
     * this class's own shutdown report, and publishing it would invite a caller to re-read it after
     * {@link #drain(Duration)} has returned — which races the very work being reported.
     */
    List<String> activeCommandDetails() {
        // Sorted so repeated reads — and a log line read by a human — have a stable order;
        // the list is bounded by config.maxConcurrency(), so the sort is free.
        return inflight.values().stream().sorted().toList();
    }

    @Override
    public <P> String submit(Command<P> command) {
        final var state = CommandState.create(command.id(), command.type(), command.params());
        store.save(state);
        queue.submit(CommandMsg.of(command.id(), command.type()));
        log.debug("Command submitted: id={}, type={}", command.id(), command.type());
        return command.id();
    }

    @Override
    public Optional<CommandState> getState(String commandId) {
        return store.findById(commandId);
    }

    @Override
    public <R> Optional<R> getResult(String commandId, Class<R> resultType) {
        return getState(commandId)
                .filter(state -> state.status() == CommandStatus.SUCCEEDED)
                .map(CommandState::result)
                .map(resultType::cast);
    }

    @Override
    public boolean cancel(String commandId) {
        // The CAS update owns the guards: a missing or already-terminal command refuses the
        // write. Reporting false is the point — a blind write could discard a result that had
        // already been recorded, leaving the caller believing a cancel took effect.
        if (!store.update(commandId, CommandState::cancelled)) {
            log.info("Command cancel did not take - id={}", commandId);
            return false;
        }
        log.info("Command cancelled: id={}", commandId);
        return true;
    }

    @Override
    public <P, R> void registerHandler(CommandHandler<P, R> handler) {
        final var registration = CommandRegistration.of(handler);
        handlers.put(handler.type(), registration);
        log.debug("Registered command handler: type={}", handler.type());
    }

    @Override
    public CommandRegistration<?, ?> getHandler(String type) {
        return handlers.get(type);
    }

    @Override
    public <P> Command<P> toCommand(CommandState state, CommandRegistration<P, ?> registration) {
        // Use Class.cast() for type-safe runtime casting
        // Safe because @JsonTypeInfo preserves type information during serialization
        final P params = registration.paramsType().cast(state.params());

        return new Command<>() {
            @Override
            public String id() {
                return state.id();
            }

            @Override
            public String type() {
                return state.type();
            }

            @Override
            public P params() {
                return params;
            }
        };
    }

    /**
     * Queue consumer entry point. Deliveries settle three ways: ACK for stale/terminal
     * messages, RETRY for transient refusals (redelivered after the visibility timeout),
     * DEFERRED when a handler task takes the entry lease and settles it on completion.
     * A throw out of this method is settled as RETRY by the queue layer.
     */
    private MessageConsumer.Decision processCommand(CommandMsg msg, MessageLease lease) {
        final var state = store.findById(msg.commandId()).orElse(null);
        if (state == null) {
            log.error("Command state not found - this should not happen: id={}", msg.commandId());
            return Decision.ACK;
        }
        if (state.status().isTerminal()) {
            return Decision.ACK;
        }
        final var registration = getHandler(state.type());
        if (registration == null) {
            log.error("No handler for command type: {}", state.type());
            return store.update(state.id(), s0 -> s0.failed("No handler for type: " + s0.type()))
                    ? Decision.ACK
                    : Decision.RETRY;
        }
        return dispatchCommand(state, registration, lease);
    }

    /**
     * Hand the delivery to a handler task. From a successful submit onward the TASK owns
     * the lease — runCommand() settles it on every exit path. A rejected submit means
     * nothing runs and nothing owns the lease: RETRY, the claim cycle re-delivers.
     */
    private <P, R> MessageConsumer.Decision dispatchCommand(
            CommandState state,
            CommandRegistration<P, R> registration,
            MessageLease lease) {

        final Command<P> command = toCommand(state, registration);   // a throw here → RETRY via the queue layer
        final CommandHandler<P, R> handler = registration.handler();
        try {
            final Future<?> task = submitCounted(() -> runCommand(command, state, handler, lease), describe(state));
            // The task is the lease's owner: bind its liveness so the queue's lease-age
            // backstop never mistakes a long-running handler for a registry leak — the
            // backstop only prunes when the owning task is provably gone.
            lease.bindLiveness(() -> !task.isDone());
        }
        catch (RuntimeException e) {
            log.error("Command dispatch rejected, will retry: id={}", state.id(), e);
            return Decision.RETRY;
        }
        return Decision.DEFERRED;
    }

    /**
     * Runs on the blocking executor; the entry lease is renewed for as long as this
     * takes, so a slow handler's entry can never go stalled mid-flight. The finally guarantees
     * every exit — including an Error out of handler code — settles the lease; the
     * idempotent settle makes the happy-path ack and the finally's retry compose.
     */
    private <P, R> void runCommand(Command<P> command, CommandState state,
                                   CommandHandler<P, R> handler, MessageLease lease) {
        try {
            // Terminal snapshot: unreachable via processCommand()'s terminal pre-check,
            // but this method stays total rather than trusting its caller — the message
            // is stale: ack. Handled BEFORE the handler routing, on its own branch, so
            // no handler-result sentinel can ever be confused with it.
            if (state.status().isTerminal()) {
                lease.ack();
                return;
            }
            // Shutting down: this delivery was claimed before the shutdown began — usually
            // microseconds before, though a task that queued behind other executor work reaches
            // this same check later — and starting the handler now can only add work the drain has
            // to wait out. One cloud call can be given a budget longer than the whole drain budget,
            // so it cannot be waited out at all. Nothing has been mutated yet, so there is nothing
            // to protect by proceeding and nothing to roll back: do not route. The delivery is
            // settled as a retry, so it is redelivered on the claim cadence — to a replica that is
            // not shutting down, or to this process after a restart — and runs from exactly this
            // state. For a PROCESSING command that means the next poll lands on the claim cadence
            // rather than checkStatusInterval, which only ever polls sooner, never later.
            // Checked AFTER the terminal branch, so a stale message is still acked here rather than
            // left to be redelivered into the next process.
            if (draining) {
                log.debug("Command not started - service is draining: id={}, status={}", state.id(), state.status());
                lease.retry();
                return;
            }
            // Route explicitly on the snapshot status, every value named. Terminal
            // statuses are acked above, so reaching their arm is a routing bug, and an
            // unmapped new status must never silently execute — both throw, landing in
            // the retry-on-throw catch below.
            final CommandResult<R> result = switch (state.status()) {
                // PROCESSING is the handler's own earlier declaration (an execute() that
                // returned PROCESSING) — never the queue's impatience — so checkStatus() is
                // only invoked on the async-work pattern it was written for.
                case PROCESSING -> handler.checkStatus(command, state);
                // PENDING executes; after a crash the state is still PENDING, and the
                // message lease guarantees the crashed invocation is not still running on
                // a live replica.
                case PENDING -> handler.execute(command);
                case SUCCEEDED, FAILED, CANCELLED -> throw new IllegalStateException("Terminal status must be acked before routing - id=" + state.id() + "; status=" + state.status());
                default -> throw new IllegalStateException("Unmapped command status: " + state.status() + " - id=" + state.id());
            };
            // A null result is a handler bug and must stay RETRYABLE: it lands in the
            // retry-on-throw catch below, exactly as it did before the explicit routing
            // (result.status() would have thrown). It must never double as a stale-message
            // sentinel — acking would remove a live command's only message, stranding it
            // with no retry driver left.
            Objects.requireNonNull(result, () -> "Handler returned a null command result - id=" + state.id() + "; status=" + state.status());

            if (result.status() == CommandStatus.PROCESSING) {
                // The handler declared async work in flight: record the declaration, then
                // schedule the next checkStatus() poll on the re-poll cadence — decoupled
                // from the visibility timeout, which paces crash detection and error retries;
                // tightening that clock must not multiply the polling load.
                recordProcessingDeclaration(state);
                lease.retryAfter(config.checkStatusInterval());
                return;
            }

            // Terminal result, recorded via the CAS update: a refusal means the command went
            // terminal underneath (a cancel won) — the redelivery acks on the terminal
            // check, so RETRY loses nothing.
            if (store.update(state.id(), s0 -> s0.applyResult(result))) {
                log.debug("Command completed: id={}, status={}", state.id(), result.status());
                lease.ack();
            }
            else {
                settleUnrecordedResult(state, lease);
            }
        }
        catch (Exception e) {
            // Retry-on-throw (#890), now uniform: the state is unchanged, so the redelivery
            // re-executes a PENDING command or re-polls a PROCESSING one. No rollback needed:
            // there is no queue-invented PROCESSING to roll back.
            log.error("Command processing errored, will retry: id={}", command.id(), e);
            recordError(state, e);
            lease.retry();
        }
        finally {
            // Backstop for non-Exception Throwables (OOME, NoClassDefFoundError out of
            // handler code): a lease must never outlive its task. Idempotent — a no-op
            // when a branch above already settled.
            lease.retry();
        }
    }

    /**
     * Settle a delivery whose terminal-result write was refused. Two very different causes
     * hide behind that refusal and must not read as one: a VERIFIED terminal (or missing)
     * state means a cancel — or expiry — won underneath; the result is dropped by design and
     * the stale message acks now instead of burning one more redelivery on the terminal
     * pre-check. Anything else is the theoretical exhaustion of the CAS retry bound against
     * a LIVE command — retried via redelivery, loudly: mislabeling it as terminal would hide
     * a command looping on redelivery until its state TTL.
     */
    private void settleUnrecordedResult(CommandState state, MessageLease lease) {
        final CommandStatus status = store.findById(state.id()).map(CommandState::status).orElse(null);
        if (status == null || status.isTerminal()) {
            log.info("Command result not recorded, already terminal: id={}; status={}", state.id(), status);
            lease.ack();
            return;
        }
        log.warn("Command result write did not converge, will retry: id={}; status={}", state.id(), status);
        lease.retry();
    }

    /**
     * Submit the handler task to the pool, registering it for exactly as long as it actually runs —
     * the work a drain waits for, the admission cap gates on, and a shutdown report names.
     *
     * <p>The two unregister sites are mutually exclusive, so one submission can never remove twice:
     * the task's own {@code finally} runs iff the task ran, and the catch below runs iff the executor
     * rejected the submission, in which case the task never ran at all. Without that catch a rejection
     * leaks the entry for good: every later {@link #drain} reports false, the readiness indicator shows
     * phantom activeTasks forever, and the admission cap starves the dispatcher.
     *
     * @param task the handler invocation to run on the executor
     * @param detail how the invocation is named in {@link #activeCommandDetails()}
     */
    private Future<?> submitCounted(Runnable task, String detail) {
        final long seq = inflightSeq.incrementAndGet();
        inflight.put(seq, detail);
        try {
            return executor.submit(() -> {
                try {
                    task.run();
                }
                finally {
                    inflight.remove(seq);
                }
            });
        }
        catch (RuntimeException e) {
            inflight.remove(seq);
            throw e;
        }
    }

    /**
     * How an in-flight invocation is named in {@link #activeCommandDetails()}: the command id
     * carries the correlation a responder greps for, the type says what kind of work it is (the
     * difference between an expected slow launch and something unexpected).
     */
    private static String describe(CommandState state) {
        return state.id() + "(" + state.type() + ")";
    }

    /**
     * Record a handler's PROCESSING declaration against the persisted state. Note the two
     * distinct subjects: the handler's <em>result</em> said PROCESSING (checked by the caller);
     * what happens next depends on what the persisted <em>state</em> already says. The first
     * declaration (state still PENDING — the execute() that just kicked off async work)
     * writes the transition, which is what routes the next delivery to {@code checkStatus()}.
     * Subsequent polls (state already PROCESSING) are write-free, except to clear a recovered
     * error streak — and only when there is one, so healthy re-polls stay write-free.
     */
    private void recordProcessingDeclaration(CommandState state) {
        if (state.status() != CommandStatus.PROCESSING) {
            markProcessing(state);
        }
        else if (state.errorsCount() > 0) {
            store.update(state.id(), CommandState::clearErrors);
        }
    }

    /**
     * Mark the command PROCESSING so the next delivery polls {@code checkStatus()} instead of
     * running {@code execute()} again — used when a handler explicitly returned a PROCESSING
     * result. A silently skipped write here is not benign: the message is redelivered with the state
     * still PENDING, and a second execution starts while the async work it declared is in flight. With
     * the compare-and-swap {@code update()} a refusal from mere contention no longer exists; the
     * single retry covers only the theoretical exhaustion of the CAS bound, and a persistent miss
     * is logged loudly. That warning is alert-worthy: any occurrence marks the
     * duplicate-execution precondition actually firing. The refusal can also be the terminal
     * guard (the command was cancelled mid-execution); that one is harmless — the redelivery is
     * acked on the terminal check — so it is not warned about.
     */
    private void markProcessing(CommandState state) {
        // one retry only — it covers just the theoretical exhaustion of the CAS bound
        for (int attempt = 0; attempt < 2; attempt++) {
            if (store.update(state.id(), CommandState::toProcessing)) {
                return;
            }
        }
        final CommandStatus status = store.findById(state.id()).map(CommandState::status).orElse(null);
        if (status == null || !status.isTerminal()) {
            log.warn("Command PROCESSING mark not recorded - id={}, status={}; a redelivery may start a second execution", state.id(), status);
        }
    }

    /**
     * Best-effort: record a non-terminal processing error on the command state — increment the
     * consecutive-error count and capture the message — for observability of a retry storm on a
     * command that stays retryable. A failure to persist this must not change control flow: the
     * command is kept in the queue and retried regardless.
     */
    private void recordError(CommandState state, Exception e) {
        try {
            store.update(state.id(), s0 -> s0.withError(rootMessage(e)));
        } catch (Exception fail) {
            log.warn("Failed to record command error state: id={}", state.id(), fail);
        }
    }

    /**
     * The most specific message available for a processing error. Handlers may wrap the
     * underlying failure in generic layers (e.g. {@code RuntimeException("Command execution
     * failed")}), so the root cause's message is recorded instead — otherwise every transient
     * error would read as the wrapper and the field would be useless for diagnosing a retry
     * storm. An unwrapped exception is its own root cause.
     */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.toString();
    }
}
