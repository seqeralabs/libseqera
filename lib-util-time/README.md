# lib-util-time

Utility functions for handling time durations in Seqera platform components.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-util-time:1.0.0'
}
```

## Usage

Generate random durations within a specified range:

```java
// Random duration between min and max
Duration random = DurationUtils.randomDuration(Duration.ofSeconds(1), Duration.ofSeconds(10));

// Random duration within a percentage interval of a reference duration
// e.g. 100s +/- 20% → random between 80s and 120s
Duration jittered = DurationUtils.randomDuration(Duration.ofSeconds(100), 0.2f);
```

## Testing

```bash
./gradlew :lib-util-time:test
```
