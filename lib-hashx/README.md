# lib-hashx

Hashing utilities for computing deterministic hash values from various input types.

## Overview

Provides a simple, fluent API for computing hashes with two implementations:
- **Sha256Hasher**: SHA-256 based hasher for cryptographic-quality hashing
- **Sip24Hasher**: SipHash 2-4 based hasher for fast, high-quality non-cryptographic hashing

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-hashx:1.0.0'
}
```

## Usage

```java
import io.seqera.lib.hash.Hasher;
import io.seqera.lib.hash.Sha256Hasher;
import io.seqera.lib.hash.Sip24Hasher;

// SHA-256 based hashing
Hasher hasher = new Sha256Hasher();
long hash = hasher
    .putString("hello")
    .putInt(42)
    .putBoolean(true)
    .toLong();

// SipHash 2-4 based hashing (faster, non-cryptographic)
Hasher sipHasher = new Sip24Hasher();
long fastHash = sipHasher
    .putString("key")
    .putSeparator()
    .putString("value")
    .toLong();
```

### Using separators

Separators help prevent hash collisions when concatenating fields:

```java
// Without separator: "ab" + "cd" could collide with "abc" + "d"
// With separator: fields are properly delimited
long hash = new Sha256Hasher()
    .putString("ab")
    .putSeparator()
    .putString("cd")
    .toLong();
```

## API

### Hasher Interface

| Method | Description |
|--------|-------------|
| `Hasher putString(String value)` | Add a string to the hash (null-safe, treated as no-op) |
| `Hasher putBoolean(boolean value)` | Add a boolean to the hash |
| `Hasher putInt(int value)` | Add an integer to the hash |
| `Hasher putSeparator()` | Add a delimiter to prevent field collisions |
| `long toLong()` | Compute and return the final hash value |

### Implementations

| Implementation | Use Case |
|---------------|----------|
| `Sha256Hasher` | When cryptographic properties are needed |
| `Sip24Hasher` | When speed is preferred over cryptographic strength |

## Testing

```bash
./gradlew :lib-hashx:test
```
