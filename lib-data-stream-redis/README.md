# lib-data-stream-redis

Message streaming with Redis Streams and local implementations for persistent event processing.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-data-stream-redis:1.0.0'
}
```

## Usage

Event streaming with consumer groups and message acknowledgment:

```groovy
@Inject
MessageStream<ActivityEvent> messageStream

// Initialize stream
messageStream.init("user-activity")

// Publish events
def event = new ActivityEvent(
    userId: "user123",
    action: "login",
    timestamp: Instant.now()
)
messageStream.offer("user-activity", event)

// Consume events
class ActivityConsumer implements MessageConsumer<ActivityEvent> {
    @Override
    boolean consume(ActivityEvent event) {
        analyticsService.recordActivity(event)
        return true // Acknowledge message
    }
}

messageStream.consume("user-activity", new ActivityConsumer())
```

## Testing

```bash
./gradlew :lib-data-stream-redis:test
```