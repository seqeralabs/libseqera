# lib-crypto

Cryptographic utilities for secure operations in Seqera platform components.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-crypto:1.0.0'
}
```

## Usage

Secure encryption, digital signatures, and token management:

```groovy
@Inject
CryptoHelper cryptoHelper

// Asymmetric encryption
def cipher = new AsymmetricCipher()
def encrypted = cipher.encrypt(sensitiveData, publicKey)
def decrypted = cipher.decrypt(encrypted, privateKey)

// Digital signatures
def signature = new HmacSha1Signature(secretKey)
def signed = signature.sign(data)
def isValid = signature.verify(data, signed)

// Secure tokens
def token = TokenHelper.createToken(userPayload, Duration.ofHours(24))
def isValidToken = TokenHelper.validateToken(token, secretKey)
```

## Testing

```bash
./gradlew :lib-crypto:test
```