# lib-random

Cryptographically secure random key generation and unique identifier utilities.

## Usage

Secure random number generation with multiple encoding formats:

```groovy
import io.seqera.random.LongRndKey

// Generate random keys
def longKey = LongRndKey.rndLong()                // Returns Long
def stringKey = LongRndKey.rndLongAsString()      // Returns 15-digit String
def hexKey = LongRndKey.rndHex()                  // Returns 12-digit hex String

```

## Testing

```bash
./gradlew :lib-random:test
```
