# lib-cmd-queue-redis

Asynchronous command queue for executing long-running tasks with persistent state tracking and automatic status polling.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cmd-queue-redis:0.7.0'
}
```

## Features

- Fire-and-forget command submission
- Typed parameters and results with JSON serialization
- Status transitions: `PENDING` → `PROCESSING` → `SUCCEEDED`/`FAILED`/`CANCELLED`
- Non-blocking, concurrent handler execution on virtual threads (no per-command timeout)
- Periodic status checking for async commands via in-process re-polling
- Command cancellation support
- Persistent storage using Redis or in-memory backend

## Usage

### Define Command Parameters and Result

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

// Stop consuming commands (e.g. during shutdown)
commandService.stop();
```

## Metrics (optional)

Since `0.4.0`, `CommandQueue` exposes a second constructor that forwards an optional
[`QueueMetrics`](https://github.com/seqeralabs/libseqera/tree/master/lib-data-workqueue)
handle to the underlying `AbstractWorkQueue`. Subclasses that want to publish
Micrometer metrics construct a `MicrometerQueueMetrics` from a `MeterRegistry` and pass
it through:

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.seqera.data.workqueue.metrics.MicrometerQueueMetrics;

public class MyCommandQueue extends CommandQueue {

    @Inject
    public MyCommandQueue(WorkQueue<String> target, CommandConfig config, @Nullable MeterRegistry registry) {
        super(target, config, registry != null
                ? new MicrometerQueueMetrics(registry, "my-cmd-queue")
                : null);
    }

    @Override protected String name() { return "my-cmd-queue"; }
}
```

The 1-arg constructor is unchanged: existing subclasses continue to compile and run
with no metrics. See [`lib-data-workqueue`](../lib-data-workqueue/README.md) for the
list of published meters (`seqera.workqueue.entries`, `seqera.workqueue.messages`,
`seqera.workqueue.processing`) and their tags.

## Architecture

Under the hood the module splits a command into two independently stored parts:
a lightweight **message** that flows through a queue, and the **full state**
(params, result, status, timings) that lives in a persistent store. The queue is
just transport; the store is the source of truth.

```
   submit(command)
        │  persist PENDING state + enqueue CommandMsg (fire-and-forget)
        ▼
  ┌──────────────┐  save() ┌──────────────────────────────────────────────┐
  │ CommandState │◀────────┤              CommandServiceImpl                │
  │    store     │  find() │  processCommand(msg): load state, then         │──▶ execute()
  │ (Redis/mem)  │────────▶│  dispatch the handler to a worker pool         │    checkStatus()
  │              │         │  (off the dispatcher thread; virtual threads): │
  └──────────────┘         │    • terminal     → ack (remove from queue)    │
        ▲                  │    • processing() → keep lease, re-poll after  │
        │ getState/Result  │                     pollInterval (in-process)  │
        │                  └───────────────────────┬────────────────────────┘
        │                       submit(msg)         │  addConsumer(processCommand)
        │                                           ▼
        │                     ┌────────────────────────────────────────────┐
        └─────────────────────│  CommandQueue (Redis work queue / in-mem.)  │
                              │  = AbstractWorkQueue: dispatcher + worker   │
                              │  pool + heartbeat lease → exactly one live  │
                              │  runner per command, no timeout             │
                              └────────────────────────────────────────────┘
```

### Components

| Component | Role |
|-----------|------|
| `CommandService` | Public facade: `submit`, `getState`, `getResult`, `cancel`, `registerHandler`, `start`/`stop`. |
| `CommandQueue` | Abstract `AbstractWorkQueue<CommandMsg>` (from `lib-data-workqueue-redis`). Carries only `CommandMsg` (id + type), Moshi-encoded. Backed by a Redis work queue or an in-memory queue. |
| `CommandStateStore` | Abstract `AbstractStateStore<CommandState>` (from `lib-data-store-state-redis`). Holds the full JSON state with a TTL (default 7 days). Backed by Redis or in-memory. |
| `CommandHandler<P,R>` | User code: `execute()` runs the work; optional `checkStatus()` polls a long-running/external job. |
| `CommandState` | Persisted record (params + result via `@JsonTypeInfo`, status, timings). The source of truth. |
| `CommandMsg` | Minimal queue pointer — just `commandId` + `type`; the payload is looked up from the store on delivery. |

Backend selection is automatic: when a `RedisActivator` bean is present both the
queue and the store use Redis; otherwise they fall back to in-memory
implementations (useful for tests and single-node setups).

### Submit path

`submit()` is fire-and-forget: it persists a `PENDING` `CommandState` to the
store, then enqueues a `CommandMsg`, and returns the command id immediately. No
handler runs on the caller's thread.

### Processing loop

`start()` supplies the shared Micronaut `BLOCKING` (virtual-thread) executor to the
queue and registers `processCommand` as the consumer. The queue's dispatcher thread
never runs a handler itself: it hands each delivered `CommandMsg` to the executor and
returns immediately, so a slow handler never blocks intake. The consumer returns a
boolean:

- **`true`** → terminal; the message is acknowledged and removed.
- **`false`** → not yet terminal; the command **keeps its lease** and `processCommand`
  is re-invoked in-process after `pollInterval` (the poll loop for long-running commands).

For each delivery, `processCommand` loads the state and decides:

1. **State missing or already terminal** → `true`; nothing to do (another replica finished it, or it was cancelled).
2. **No handler registered** → mark `FAILED`, `true`.
3. **State is `PENDING`** → run `handler.execute()`. Terminal result → apply, `true`;
   `processing()` → mark `PROCESSING`, `false` (re-polled after `pollInterval`).
4. **State is `PROCESSING`** → run `handler.checkStatus()`. Terminal → `true`;
   `processing()` → `false` (re-polled again).

A quick command finishes in one delivery; a slow or external one flips to `PROCESSING`
and is driven to completion by repeated `checkStatus()` calls at `pollInterval`
cadence. Handler exceptions transition the command to `FAILED` and ack. There is no
per-command timeout and no per-command lock — the handler runs to completion on a
virtual thread, and the underlying work queue's per-message lease guarantees a single
concurrent runner across replicas (see
[`lib-data-workqueue-redis`](../lib-data-workqueue-redis/README.md)).

### Multi-replica behaviour

The work queue's per-message lease (a heartbeated Redis consumer-group entry) ensures a
command is processed by exactly one live replica at a time; if that replica dies, the
lease lapses and a peer reclaims the command. The store is the shared source of truth,
so the terminal-state check keeps processing idempotent. Delivery is **at-least-once**,
so `execute()`/`checkStatus()` should be idempotent.

## Command Status Flow

```
submit() ──▶ PENDING ──pickup──▶ PROCESSING ─┬─success──▶ SUCCEEDED
                                             ├─error────▶ FAILED
                                             └─cancel───▶ CANCELLED

(new state persists as PENDING/PROCESSING; legacy SUBMITTED/RUNNING entries still decode.
Upgrading across this rename requires a zero-overlap rollout — see changelog 0.7.0.)
```

## Testing

```bash
./gradlew :lib-cmd-queue-redis:test
```

## License

Apache License 2.0
