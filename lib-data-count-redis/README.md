# lib-data-count-redis

Distributed counter abstraction modelled after Redis `INCRBY`/`DECRBY` commands. Provides a local (in-memory) and Redis-backed implementation, selected automatically via Micronaut conditional beans.

## Usage

### 1. Configuration-driven counters

Define named count stores in `application.yml`. Each entry under `seqera.count` creates a `DefaultCountStore` bean:

```yaml
seqera:
  count:
    tasks:
      prefix: task-counter
    builds:
      prefix: build-counter
```

If `prefix` is omitted, the configuration name is used as prefix.

Inject the store by qualifier:

```java
@Inject
@Named("tasks")
CountStore taskCounter;

// the prefix is prepended to the key with a ':' separator
// e.g. prefix "task-counter" + key "pending" = "task-counter:pending"
long count = taskCounter.increment("pending");        // +1, returns new value
long count = taskCounter.increment("pending", 5);     // +5, returns new value
long count = taskCounter.decrement("pending");        // -1, returns new value
long count = taskCounter.decrement("pending", 3);     // -3, returns new value
long current = taskCounter.get("pending");
```

### 2. Custom subclass

For more control, extend `AbstractCountStore` directly:

```java
@Singleton
public class TaskCounter extends AbstractCountStore {

    protected TaskCounter(CountProvider provider) {
        super(provider);
    }

    @Override
    protected String getPrefix() {
        return "tasks";
    }
}
```

### 3. Provider selection

The implementation is selected automatically based on the presence of a `RedisActivator` bean:

- **Redis** — when a `RedisActivator` bean is available
- **Local** — when no `RedisActivator` bean is present (uses `ConcurrentHashMap` + `AtomicLong`)

### 4. Direct provider usage

You can also inject `CountProvider` directly if you need to manage keys dynamically:

```java
@Inject
CountProvider countProvider;

countProvider.increment("my-key", 1);
countProvider.decrement("my-key", 1);
long value = countProvider.get("my-key");
```
