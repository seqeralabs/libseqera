# lib-trace

Tracing and performance monitoring utilities for distributed systems debugging.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-trace:0.1.0'
}
```

## Usage

Generate distributed trace IDs for HTTP request correlation:

```groovy
import io.seqera.util.trace.TraceUtils

// Simple HTTP client with trace header
class SimpleHttpClient {
    
    void setupConnection(HttpURLConnection con, String method, String contentType) {
        con.setRequestMethod(method)
        con.setRequestProperty("Content-Type", contentType)
        con.setRequestProperty("User-Agent", userAgent)
        con.setRequestProperty("Traceparent", TraceUtils.rndTrace())
        // Additional headers...
    }
}
```

## Testing

```bash
./gradlew :lib-trace:test
```
