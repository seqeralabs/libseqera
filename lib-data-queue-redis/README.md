# lib-data-queue-redis

Message queue abstraction with Redis and local implementations for reliable FIFO messaging.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-queue-redis:1.0.0'
}
```

## Usage

Distributed message queuing with blocking operations:

```groovy
@Inject
MessageQueue<String> messageQueue

// Send message to queue
messageQueue.offer("task-queue", "process-data")

// Receive with timeout (blocking)
String message = messageQueue.poll("task-queue", Duration.ofSeconds(30))

// Custom message types
class TaskMessage {
    String id
    String type
    Map payload
}

MessageQueue<TaskMessage> taskQueue = new RedisMessageQueue<>(TaskMessage.class)

// Send custom message
taskQueue.offer("tasks", new TaskMessage(id: "123", type: "process", payload: [data: "value"]))

// Poll for messages, likely in a separate thread in a real scenario
def task = taskQueue.poll("tasks", Duration.ofSeconds(10))
if (task != null) {
    log.info("Received task: ${task.id}")
    // Process the task...
}
```

## Testing

```bash
./gradlew :lib-data-queue-redis:test
```
