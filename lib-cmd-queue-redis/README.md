# lib-cmd-queue-redis

Asynchronous command queue for executing long-running tasks with persistent state tracking and automatic status polling.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cmd-queue-redis:0.1.0'
}
```

## Features

- Fire-and-forget command submission
- Typed parameters and results with JSON serialization
- Status transitions: `SUBMITTED` → `RUNNING` → `SUCCEEDED`/`FAILED`/`CANCELLED`
- Automatic timeout handling for long-running commands
- Periodic status checking for async commands
- Command cancellation support
- Persistent storage using Redis or in-memory backend
- Multi-queue consumption with per-queue configuration (e.g. different claim
  timeouts for slow lifecycle work vs. fast status polling)
- Cross-queue hand-off via `CommandResult.handoff(streamId)`

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
        return CommandResult.active();  // checkStatus() will be called later
    }

    @Override
    public CommandResult<ProcessingResult> checkStatus(Command<ProcessingParams> command, CommandState state) {
        var status = externalService.getStatus(command.id());
        if (status.isComplete()) return CommandResult.success(status.getResult());
        if (status.isFailed()) return CommandResult.failure(status.getError());
        return CommandResult.active();  // Still active, check again later
    }
}
```

> `CommandResult.running()` is kept as a deprecated alias for `active()`.
> Prefer `active()` in new code — the name avoids clashing with domain-level
> "running" statuses (e.g. a container that is actually executing).

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

## Command Status Flow

```
submit() ──▶ SUBMITTED ──pickup──▶ RUNNING ─┬─success──▶ SUCCEEDED
                                            ├─error────▶ FAILED
                                            └─cancel───▶ CANCELLED
```

## Multiple queues and cross-queue hand-off

A single `CommandService` can consume from more than one `CommandQueue` at the
same time, each with its own underlying `MessageStream` and configuration. This
is useful when different phases of a command's lifecycle benefit from different
delivery semantics — for example, a long claim-timeout for slow synchronous
work (crash-safe against consumer reclaim) and a short claim-timeout for fast
polling (low detection lag for state transitions).

### Attaching an additional queue

```java
@Inject CommandService commandService;
@Inject @Named("monitor") CommandQueue monitorQueue;

commandService.registerHandler(new MyHandler());
commandService.attachQueue(monitorQueue);   // must be called before start()
commandService.start();
```

The service consumes from the primary queue *and* from every attached queue,
dispatching to the same handler registry and state store. `attachQueue`
validates input: it rejects `null`, the same instance twice, duplicate stream
names, and stream-name collisions with the primary queue. Each attached queue
retains its own `@PreDestroy` lifecycle — `commandService.stop()` closes only
the primary queue; attached queues are closed by whatever bean owns them.

### Handing a command off between queues

A handler can end a command's life on its current queue and continue it on
another attached queue by returning `CommandResult.handoff(dstStreamId)`. The
framework ACKs the source message and offers a fresh delivery on the
destination via the queue that owns it (correct consumer group, correct
config). If the destination is unknown to the service the command is marked
`FAILED` with a descriptive error rather than silently orphaned.

```java
@Override
public CommandResult<MyResult> execute(Command<MyParams> command) {
    backend.startJob(command.id(), command.params());
    // Heavy synchronous work is done; subsequent polling should run on the
    // monitor queue with its short claim-timeout.
    return CommandResult.handoff("cmd-monitor/v1");
}
```

From the source queue's perspective `handoff` is terminal (the message is
ACKed). From the command's lifecycle perspective it remains non-terminal —
subsequent `checkStatus()` invocations happen on the destination queue and
drive the command to `SUCCEEDED` / `FAILED` / `CANCELLED` there.

Hand-off is not atomic: the destination offer happens before the source ACK.
If the consumer crashes between the two the source message is redelivered,
so handlers that rely on `handoff` must be idempotent under such redelivery
(typically: persist an intermediate state before the side-effect so the
retried invocation can skip it).

## Testing

```bash
./gradlew :lib-cmd-queue-redis:test
```

## License

Apache License 2.0
