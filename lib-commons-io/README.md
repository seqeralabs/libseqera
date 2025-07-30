# lib-commons-io

Common I/O utilities for logging and streaming in Seqera platform components.

## Installation

Add this dependency to your `build.gradle`:

```gradle
dependencies {
    implementation 'io.seqera:lib-commons-io:1.0.0'
}
```

## Usage

Stream processing utilities for logging external process output:

```groovy
// Custom log output stream implementation
class CustomLogStream extends LogOutputStream {
    @Override
    protected void processLine(String line, int logLevel) {
        println "LOG[$logLevel]: $line"
    }
}

// Usage with external process
def process = new ProcessBuilder("some-command").start()
def logStream = new CustomLogStream()

// Redirect process output to log stream
process.inputStream.transferTo(logStream)
logStream.close()
```

## Testing

```bash
./gradlew :lib-commons-io:test
```