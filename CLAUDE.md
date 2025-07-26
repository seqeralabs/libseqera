# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

This is a multi-module Gradle project with Java and Groovy libraries for Seqera platform components.

### Essential Commands
- `./gradlew check` - Run all tests and checks across all modules
- `./gradlew assemble` - Compile all modules
- `./gradlew :module-name:test` - Run tests for a specific module (e.g., `./gradlew :lib-crypto:test`)
- `./gradlew :module-name:build` - Build a specific module
- `make check` - Alternative way to run tests via Makefile
- `make compile` - Alternative way to compile via Makefile

### Dependencies and Analysis
- `make deps` - Show runtime dependencies for all modules
- `make deps module=module-name` - Show dependencies for specific module
- `make deps config=configuration` - Show dependencies for specific configuration (e.g., `make deps config=testRuntimeClasspath`)

## Project Architecture

### Module Structure
This is a library collection (`libseqera`) containing reusable components for Seqera platform projects:

#### Core Libraries
- **wave-api**: API models and DTOs for Wave container service
- **wave-utils**: Utility classes for Wave (Docker helpers, file operations, template rendering)
- **lib-crypto**: Cryptographic utilities (asymmetric encryption, HMAC signatures, secure tokens)
- **lib-mail**: Email functionality with Micronaut integration
- **lib-retry**: Retry mechanisms with exponential backoff
- **lib-pool**: Simple object pooling utilities
- **lib-lang**: Language and type utilities
- **lib-trace**: Tracing and logging utilities
- **jedis-lock**: Redis-based distributed locking using Jedis

#### Messaging & Data Libraries
- **lib-data-queue-redis**: Message queue abstraction with Redis and local implementations
- **lib-data-stream-redis**: Message streaming with Redis Streams and local implementations
- **lib-serde**: Serialization/deserialization utilities
- **lib-random**: Random key generation utilities

#### Testing Support
- **lib-fixtures-redis**: Test fixtures and containers for Redis-based testing

### Technology Stack
- **Java 21** toolchain with Java 17 compatibility
- **Groovy 4.0.24** for implementation and testing
- **Micronaut 4.8.x** for dependency injection and runtime
- **Spock Framework** for testing
- **Redis/Jedis** for messaging and distributed operations
- **Testcontainers** for integration testing

### Conventions
- Each module has its own `VERSION` file containing the version string
- All modules use the `io.seqera` group ID
- Build configurations are centralized in `buildSrc/` with convention plugins:
  - `io.seqera.java-library-conventions` - Base Java library setup
  - `io.seqera.groovy-library-conventions` - Groovy-specific additions
  - `io.seqera.java-test-fixtures-conventions` - Test fixtures support
- Tests use JUnit Platform with Spock Framework
- Micronaut libraries use minimal library plugin configuration
- All Redis-based modules depend on `lib-fixtures-redis` for testing

### Inter-module Dependencies
- Message libraries (`lib-data-*`) depend on `lib-serde` for serialization and `lib-retry` for resilience
- Test modules commonly depend on `lib-lang` for testing utilities
- Redis-based modules use `lib-fixtures-redis` for integration tests with Testcontainers

### Publishing
Libraries are published to Seqera's private Maven repository on S3 using AWS credentials.

### Release Management
- Use `[release]` as prefix in the first line of commit messages to trigger module library releases
- The release automation will detect this prefix and initiate the publishing process for updated modules