# lib-docker-cli

A lightweight Docker CLI wrapper for Java applications.

## Why CLI over docker-java?

This library uses the `docker` CLI directly via `ProcessBuilder` instead of the native docker-java library. This approach offers several advantages:

### Works on macOS Docker Desktop

The docker-java library has known issues with Docker Desktop on macOS due to socket path differences, VM networking, and platform-specific quirks. Using the CLI ensures consistent behavior because:

- Docker CLI handles all platform-specific details internally
- No socket configuration needed - uses the same mechanism as `docker` commands in terminal
- Works with Docker Desktop's HyperKit/Virtualization.framework VM transparently

### Simpler Debugging

When something goes wrong, you can:
- Copy the exact command from logs and run it manually
- See the actual error messages from Docker
- No abstraction layers hiding the real issue

### No Native Dependencies

The docker-java library requires:
- Transport layer configuration (Jersey, Apache HTTP, etc.)
- Socket path configuration that varies by platform
- Handling of TLS certificates for remote connections

This library requires only:
- `docker` CLI installed and in PATH
- Docker daemon running

### Consistent Behavior

The CLI provides the same behavior across:
- Linux (native Docker)
- macOS (Docker Desktop)
- Windows (Docker Desktop)
- CI/CD environments

## Usage

### Basic Container Operations

```java
DockerCli docker = new DockerCli();

// Run a container
ContainerConfig config = new ContainerConfig()
    .name("my-container")
    .image("alpine:latest")
    .command(List.of("echo", "Hello, World!"));
String containerId = docker.run(config);

// Get container state
ContainerState state = docker.inspect("my-container");
if (state.isRunning()) {
    System.out.println("Container is running");
}

// Get logs
String logs = docker.logs("my-container");

// Stop and remove
docker.stop("my-container");
docker.rm("my-container");
```

### With FUSE Support (for Fusion filesystem)

```java
ContainerConfig config = new ContainerConfig()
    .name("fusion-job")
    .image("my-image:latest")
    .withFuseSupport()  // Enables privileged, SYS_ADMIN, /dev/fuse
    .env("AWS_ACCESS_KEY_ID", accessKey)
    .env("AWS_SECRET_ACCESS_KEY", secretKey);

docker.run(config);
```

### Check Docker Availability

```java
if (DockerCli.isAvailable()) {
    // Docker is installed and daemon is running
}
```

## License

Apache License 2.0
