# lib-cmd-queue-redis

> **1.0.0 replaces the reverted 0.5–0.7 line.** This is the lease-based command queue that
> [#84](https://github.com/seqeralabs/libseqera/pull/84)/[#86](https://github.com/seqeralabs/libseqera/pull/86)/[#87](https://github.com/seqeralabs/libseqera/pull/87)/[#89](https://github.com/seqeralabs/libseqera/pull/89)
> built and [#100](https://github.com/seqeralabs/libseqera/pull/100) withdrew, re-landed on
> top of [`lib-data-workqueue`](../lib-data-workqueue/README.md) 2.0.0 with the hardening the
> scheduler added while running it: a per-command write mutex guarding `CommandState`
> transitions, configurable lock timings and a caller-budgeted `close()`, a retry-safe
> PROCESSING mark (`markProcessing()`), rejection-safe in-flight counting (`submitCounted()`),
> and a wait-once `close()`.
>
> **This is a breaking change over 0.4.0**, whose `CommandServiceImpl` dispatched
> synchronously on `lib-data-stream-redis` with no lease. `CommandQueue` now extends
> `AbstractWorkQueue<CommandMsg>`; `queueName()` (was `streamName()`) still returns
> `name() + "/v1"`, so Redis keys and the consumer group are untouched.
>
> `CommandState` transitions use the versioned compare-and-swap of
> `lib-data-store-state-redis` 1.2.0+: `CommandState` implements `VersionAware` and the CAS
> witness is a store-stamped version, not re-serialized bytes — byte-equality CAS broke
> cross-replica under JVM-dependent property ordering.
>
> `CommandStatus` follows #86's naming: `SUBMITTED` → `PENDING` and `RUNNING` → `PROCESSING`,
> with `@JsonAlias` on both. **The aliases are load-bearing and must never be removed** —
> `CommandState` is persisted as Jackson-encoded JSON with the status as a bare enum name, so
> state written by the previous naming only deserializes because of them.
>
> **Versions 0.5.0, 0.5.1, 0.6.0 and 0.7.0 remain published but are abandoned** (reverted by
> #100). Do not depend on them; upgrade from 0.4.0 straight to 1.0.0. See `changelog.txt` for
> the downgrade hazard on command state written by 0.7.0.

## Installation

```gradle
dependencies {
    implementation 'io.seqera:lib-cmd-queue-redis:1.0.0'
}
```

### Define Command Parameters

```java
// Command parameters - must have default constructor for Jackson
public class ProcessingParams {
    private String datasetId;
    private List<String> steps;
    // Getters, setters, constructors...
}

// Command result
public class ProcessingResult {
    private int recordsProcessed;
    private long durationMs;
    // Getters, setters, constructors...
}
```

### Implement a Command Handler

For **synchronous** commands that complete quickly:

```java
@Singleton
public class ProcessingHandler implements CommandHandler<ProcessingParams, ProcessingResult> {

    @Override
    public String type() { return "data-processing"; }

    @Override
    public CommandResult<ProcessingResult> execute(Command<ProcessingParams> command) {
        var params = command.params();
        // Do the work...
        var result = new ProcessingResult(1000, 5000L);
        return CommandResult.success(result);
    }
}
```

For **asynchronous** long-running commands:

```java
@Singleton
public class AsyncProcessingHandler implements CommandHandler<ProcessingParams, ProcessingResult> {

    @Inject ExternalService externalService;

    @Override
    public String type() { return "async-processing"; }

    @Override
    public CommandResult<ProcessingResult> execute(Command<ProcessingParams> command) {
        // Start async job
        externalService.startJob(command.id(), command.params());
        return CommandResult.processing();  // checkStatus() will be called later
    }

    @Override
    public CommandResult<ProcessingResult> checkStatus(Command<ProcessingParams> command, CommandState state) {
        var status = externalService.getStatus(command.id());
        if (status.isComplete()) return CommandResult.success(status.getResult());
        if (status.isFailed()) return CommandResult.failure(status.getError());
        return CommandResult.processing();  // Still processing, check again later
    }
}
```

### Submit Commands

```java
@Inject
private CommandService commandService;

// Register handlers before starting the service
commandService.registerHandler(new ProcessingHandler());

// Start consuming commands from the queue
// Must be called AFTER all handlers are registered
commandService.start();

// Submit a command
var command = new ProcessingCommand("cmd-123", params);
String commandId = commandService.submit(command);

// Check status
Optional<CommandState> state = commandService.getState(commandId);

// Get result when complete
ProcessingResult result = commandService.getResult(commandId, ProcessingResult.class).orElseThrow();

// Graceful shutdown: refuse new work, wait (bounded) for in-flight handlers to
// finish while collaborators are still usable, then release the queue. Returns
// false when work was still running at the deadline. activeCommands() reports
// the in-flight count for readiness probes.
commandService.drain(Duration.ofSeconds(20));

// Immediate variant — releases the queue without waiting for in-flight handlers
commandService.stop();
```

## Metrics (optional)

Since `0.4.0`, `CommandQueue` exposes a second constructor that forwards an optional
[`QueueMetrics`](../lib-data-workqueue/README.md#metrics-optional) handle to the underlying
`AbstractWorkQueue`. Subclasses that want to publish Micrometer metrics construct a
`MicrometerQueueMetrics` from a `MeterRegistry` and pass it through:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.seqera.data.workqueue.WorkQueue;
import io.seqera.data.workqueue.metrics.MicrometerQueueMetrics;

public class MyCommandQueue extends CommandQueue {

    @Inject
    public MyCommandQueue(WorkQueue<String> target, @Nullable MeterRegistry registry) {
        super(target, registry != null
                ? new MicrometerQueueMetrics(registry, "my-cmd-queue")
                : null);
    }

    @Override protected String name() { return "my-cmd-queue"; }
    @Override protected Duration pollInterval() { return Duration.ofSeconds(1); }
}
```

The 1-arg constructor is unchanged: existing subclasses continue to compile and run
with no metrics. See [`lib-data-workqueue`](../lib-data-workqueue/README.md) for the
list of published meters (`seqera.workqueue.entries`, `seqera.workqueue.messages`,
`seqera.workqueue.processing`) and their tags.

## Command Status Flow

```
submit() ──▶ PENDING ──pickup──▶ PROCESSING ─┬─success──▶ SUCCEEDED
                                             ├─error────▶ FAILED
                                             └─cancel───▶ CANCELLED
```

A handler that **throws** is not terminally failed: the message stays queued and is
redelivered, with the consecutive-error streak tracked on the state (`errorsCount`,
`error`). A *permanent* failure is signalled by returning a FAILED `CommandResult` —
deciding that is the domain layer's job, never the queue's (see seqeralabs/sched#712, seqeralabs/sched#890).

## Testing

```bash
./gradlew :lib-cmd-queue-redis:test
```

## Design notes

- [`docs/plans/command-execution-guarantee-message-lease.md`](../docs/plans/command-execution-guarantee-message-lease.md)
  — the execution guarantee the message lease provides, and why the lease is settled by the
  handler rather than the dispatcher.
- [`docs/plans/2026-07-31-command-state-write-mutex-design.md`](../docs/plans/2026-07-31-command-state-write-mutex-design.md)
  — the per-command write mutex guarding `CommandState` transitions, and the alternatives
  rejected on the way to it.

## License

Apache License 2.0
