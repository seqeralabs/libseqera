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

## Testing

```bash
./gradlew :lib-cmd-queue-redis:test
```

## License

Apache License 2.0
