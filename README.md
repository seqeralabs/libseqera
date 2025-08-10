# libseqera

A collection of reusable Java & Groovy libraries for Seqera platform components. This multi-module project provides common functionality across Wave and other Seqera services.

## Modules

### Core Libraries
- **[wave-api](wave-api/)** - API models and DTOs for Wave container service
- **[wave-utils](wave-utils/)** - Utility classes for Wave (Docker helpers, file operations, template rendering)
- **[lib-activator](lib-activator/)** - Conditional activation markers for enabling components based on infrastructure availability
- **[lib-crypto](lib-crypto/)** - Cryptographic utilities (asymmetric encryption, HMAC signatures, secure tokens)
- **[lib-httpx](lib-httpx/)** - Enhanced HTTP client with automatic retry logic and JWT token refresh
- **[lib-mail](lib-mail/)** - Email functionality with Micronaut integration
- **[lib-retry](lib-retry/)** - Retry mechanisms with exponential backoff
- **[lib-pool](lib-pool/)** - Simple object pooling utilities
- **[lib-lang](lib-lang/)** - Language and type utilities
- **[lib-trace](lib-trace/)** - Tracing and logging utilities
- **[lib-commons-io](lib-commons-io/)** - Common I/O utilities for logging and streaming
- **[jedis-lock](jedis-lock/)** - Redis-based distributed locking using Jedis

### Messaging & Data Libraries
- **[lib-data-queue-redis](lib-data-queue-redis/)** - Message queue abstraction with Redis and local implementations
- **[lib-data-stream-redis](lib-data-stream-redis/)** - Message streaming with Redis Streams and local implementations
- **[lib-serde](lib-serde/)** - Serialization/deserialization utilities
- **[lib-random](lib-random/)** - Random key generation utilities

### Testing Support
- **[lib-fixtures-redis](lib-fixtures-redis/)** - Test fixtures and containers for Redis-based testing

## Technology Stack

- **Java 21** toolchain with Java 17 compatibility
- **Groovy 4.0.24** for implementation and testing
- **Micronaut 4.8.x** for dependency injection and runtime
- **Spock Framework** for testing
- **Redis/Jedis** for messaging and distributed operations
- **Testcontainers** for integration testing

## Get Started

1. Clone this repository:
   ```bash
   git clone https://github.com/seqeralabs/libseqera && cd libseqera
   ```

2. Compile & run tests:
   ```bash
   ./gradlew check
   ```

## Development

### Build Commands
- `./gradlew check` - Run all tests and checks across all modules
- `./gradlew assemble` - Compile all modules
- `./gradlew :module-name:test` - Run tests for a specific module
- `./gradlew :module-name:build` - Build a specific module

### Using Makefile
- `make check` - Run tests
- `make compile` - Compile all modules
- `make deps` - Show runtime dependencies
- `make deps module=module-name` - Show dependencies for specific module

### Module Structure
Each module follows standard conventions:
- `VERSION` file containing the version string
- `build.gradle` with module-specific configuration
- Standard `src/main/groovy` and `src/test/groovy` structure
- Spock tests using JUnit Platform

## Publishing

Libraries are published to Seqera's private Maven repository on S3 using AWS credentials.

