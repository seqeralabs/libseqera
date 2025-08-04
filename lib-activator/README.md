# lib-activator

Conditional activation markers for enabling components based on infrastructure availability.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-activator:1.0.0'
}
```

## Usage

Use activator interfaces to conditionally enable beans based on infrastructure requirements:

```java
// Redis activator interface
public interface RedisActivator {
}

// Component that requires Redis
@Singleton
@Requires(beans = RedisActivator.class)
public class RedisStreamProcessor implements StreamProcessor {
    // Redis-dependent implementation
}

// Conditional Redis activation based on environment
@Singleton
@Requires(env = "redis")
public class RedisActivationCondition implements RedisActivator {
}

// Configuration-based activation
@Singleton  
@Requires(property = "redis.uri")
public class RedisConfigActivation implements RedisActivator {
}
```

This pattern allows components to be automatically activated only when their required infrastructure is available, preventing startup failures and enabling flexible deployment configurations.

## Testing

```bash
./gradlew :lib-activator:test
```