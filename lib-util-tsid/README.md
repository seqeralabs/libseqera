# lib-util-tsid

Seeds the [TSID Creator](https://github.com/f4b6a3/tsid-creator) node ID from a coordinated [NodeId](../lib-node-id-redis/) at application startup, so each replica of a horizontally scaled service generates collision-free TSIDs.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-util-tsid:1.0.0'
}
```

## How it works

`TsidCreator` reads its node ID from the `tsidcreator.node` system property at first use, via a lazily-initialised factory holder. `TsidNodeConfigurer` is an eager (`@Context`) Micronaut bean that sets the property during startup — well before the first `TsidCreator` call:

- `tsidcreator.node` is set to the ordinal assigned by the `NodeId` bean
- `tsidcreator.node.count` is set to the `NodeId` capacity, only when it differs from the TSID default node space (1024, i.e. 10 node bits)

No code changes are needed at TSID call sites — just having this library on the classpath wires the node ID.

## Requirements

A `NodeId` bean must be available, provided by [lib-node-id-redis](../lib-node-id-redis/) (pulled in transitively): Redis-backed ordinal assignment when a `RedisActivator` bean is present, in-memory fallback otherwise.

```yaml
seqera:
  node-id:
    capacity: 1024    # Align with the TSID node-bit allocation (default: 1024 = 10 bits)
```

## Testing

```bash
./gradlew :lib-util-tsid:test
```

## License

Apache 2.0
