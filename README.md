# libseqera

A collection of reusable Java & Groovy libraries for Seqera platform components. This multi-module project provides common functionality across Wave and other Seqera services.

## Modules

### Core Libraries
- **[lib-activator](lib-activator/)** - Conditional activation markers for enabling components based on infrastructure availability
- **[lib-cloudinfo](lib-cloudinfo/)** - Client library for fetching cloud provider data (regions, products, pricing) from Cloudinfo API
- **[lib-commons-io](lib-commons-io/)** - Common I/O utilities for logging and streaming
- **[lib-crypto](lib-crypto/)** - Cryptographic utilities (asymmetric encryption, HMAC signatures, secure tokens)
- **[lib-docker-cli](lib-docker-cli/)** - Lightweight Docker CLI wrapper for Java applications
- **[lib-httpx](lib-httpx/)** - Enhanced HTTP client with automatic retry logic and JWT token refresh
- **[lib-lang](lib-lang/)** - Language and type utilities
- **[lib-mail](lib-mail/)** - Email functionality with Micronaut integration
- **[lib-pool](lib-pool/)** - Simple object pooling utilities
- **[lib-random](lib-random/)** - Random key generation utilities
- **[lib-retry](lib-retry/)** - Retry mechanisms with exponential backoff
- **[lib-trace](lib-trace/)** - Tracing and logging utilities
- **[lib-util-http](lib-util-http/)** - HTTP utilities for debugging, header dumping, and sensitive data masking
- **[lib-jedis-lock](lib-jedis-lock/)** - Redis-based distributed locking using Jedis
- **[lib-jedis-pool](lib-jedis-pool/)** - JedisPool factory with Micrometer metrics integration

### Messaging & Data Libraries
- **[lib-cache-tiered-redis](lib-cache-tiered-redis/)** - Two-tier caching with Caffeine (L1) and Redis (L2) for distributed caching
- **[lib-data-queue-redis](lib-data-queue-redis/)** - Message queue abstraction with Redis and local implementations
- **[lib-data-range-redis](lib-data-range-redis/)** - Range set storage similar to Redis ZRANGE with local and Redis implementations
- **[lib-data-store-future-redis](lib-data-store-future-redis/)** - Distributed CompletableFuture store for cross-service async operations
- **[lib-data-store-state-redis](lib-data-store-state-redis/)** - Distributed state store with atomic operations and counters
- **[lib-data-stream-redis](lib-data-stream-redis/)** - Message streaming with Redis Streams and local implementations
- **[lib-serde](lib-serde/)** - Serialization/deserialization utilities
- **[lib-serde-jackson](lib-serde-jackson/)** - Jackson JSON serialization implementing lib-serde interface
- **[lib-serde-moshi](lib-serde-moshi/)** - Moshi JSON serialization with custom adapters for common types
- **[lib-serde-gson](lib-serde-gson/)** - Gson JSON serialization implementing lib-serde interface

### Micronaut Libraries
- **[micronaut-cache-redis](micronaut-cache-redis/)** - Micronaut cache implementation using Redis

### Testing Support
- **[lib-fixtures-redis](lib-fixtures-redis/)** - Test fixtures and containers for Redis-based testing

### Deprecated
- **[wave-api](wave-api/)** - *(deprecated)* API models and DTOs for Wave container service
- **[wave-utils](wave-utils/)** - *(deprecated)* Utility classes for Wave

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

