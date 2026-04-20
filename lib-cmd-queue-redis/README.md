# lib-cmd-queue-redis

Asynchronous command queue for executing long-running tasks with persistent state tracking and automatic status polling.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-cmd-queue-redis:0.1.0'
}
```

## ⚠️ Breaking change — `CommandServiceImpl` is no longer `@Singleton`

Previous releases exposed `CommandServiceImpl` as an auto-registered
`@Singleton` bean, so consumers could just do `@Inject CommandService` and
everything worked.

From this release onward, `CommandServiceImpl` is a plain class — not a bean.
Each application must wire its own `CommandService` via a `@Factory`. This
removes an artificial single-queue assumption and lets applications produce
one service per queue (primary, monitoring, auxiliary, …) without fighting a
library default.

**Migration** — single-queue application:

```java
@Factory
public class MyCommandFactory {

    @Singleton
    public CommandService commandService(
            CommandConfig config,
            CommandStateStore store,
            CommandQueue queue,
            @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        return new CommandServiceImpl(config, store, queue, executor);
    }
}
```

Multi-queue application: see [Multiple queues](#multiple-queues-and-hand-off-between-them).

## Features

- Fire-and-forget command submission
- Typed parameters and results with JSON serialization
- Status transitions: `SUBMITTED` → `RUNNING` → `SUCCEEDED`/`FAILED`/`CANCELLED`
- Automatic timeout handling for long-running commands
- Periodic status checking for async commands
- Command cancellation support
- Persistent storage using Redis or in-memory backend
- Multiple `CommandService` instances per application (one per queue), each
  with its own consumer loop and queue configuration
- Hand-off of a command from one queue to another via
  `CommandResult.handedOff()`

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
        return CommandResult.running();  // checkStatus() will be called later
    }

    @Override
    public CommandResult<ProcessingResult> checkStatus(Command<ProcessingParams> command, CommandState state) {
        var status = externalService.getStatus(command.id());
        if (status.isComplete()) return CommandResult.success(status.getResult());
        if (status.isFailed()) return CommandResult.failure(status.getError());
        return CommandResult.running();  // Still running, check again later
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

## Command Status Flow

```
submit() ──▶ SUBMITTED ──pickup──▶ RUNNING ─┬─success──▶ SUCCEEDED
                                            ├─error────▶ FAILED
                                            └─cancel───▶ CANCELLED
```

## Multiple queues and hand-off between them

An application can run more than one `CommandService` at the same time, each
bound to its own `CommandQueue`. This is useful when different phases of a
command's lifecycle benefit from different delivery semantics — for example, a
long claim-timeout queue for slow synchronous work (crash-safe against
consumer reclaim) and a short claim-timeout queue for fast status polling.

`CommandServiceImpl` uses constructor injection, so applications can produce
additional `@Named` instances from a factory:

```java
@Factory
public class MyCommandServiceFactory {

    @Named("monitor")
    @Singleton
    public CommandQueue monitorQueue(
            JedisPool pool,
            @Named("monitor") RedisStreamConfig monitorConfig,
            CommandConfig config) {
        var monitorStream = new RedisMessageStream(pool, monitorConfig);
        return new MyMonitorQueue(monitorStream, config.pollInterval());
    }

    @Named("monitor")
    @Singleton
    public CommandService monitorService(
            @Named("monitor") CommandQueue queue,
            CommandConfig config,
            CommandStateStore store,
            @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        return new CommandServiceImpl(config, store, queue, executor);
    }
}
```

Register handlers once per service and start each loop at boot:

```java
@Inject Collection<CommandService> services;

var handlers = context.getBeansOfType(CommandHandler.class);
for (CommandService svc : services) {
    for (CommandHandler h : handlers) svc.registerHandler(h);
}
services.forEach(CommandService::start);
```

### `CommandResult.handedOff()`

A handler can end a command's life on its current queue and continue it on a
different queue. Submit the command to the destination queue directly, then
return `CommandResult.handedOff()`:

```java
@Inject @Named("monitor") CommandQueue monitorQueue;

@Override
public CommandResult<MyResult> execute(Command<MyParams> command) {
    backend.launch(...);
    // heavy synchronous work is done; move further polling to the monitor queue
    monitorQueue.submit(CommandMsg.of(command.id(), command.type()));
    return CommandResult.handedOff();
}
```

From the source queue's perspective `handedOff()` is terminal (the message is
ACKed). From the command's lifecycle perspective it remains non-terminal — the
persisted `CommandState` stays in `RUNNING`; a handler on the destination
queue eventually drives it to `SUCCEEDED` / `FAILED` / `CANCELLED`.

Hand-off is not atomic (the destination `submit` happens before the source
ACK). If the consumer crashes between the two the source message is
redelivered, so handlers that use `handedOff()` must be idempotent under
re-execution — typically by persisting an intermediate state before the
non-idempotent side-effect (e.g. the external launch call) so the replay can
skip it.

## Testing

```bash
./gradlew :lib-cmd-queue-redis:test
```

## License

Apache License 2.0
