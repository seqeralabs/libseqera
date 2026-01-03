/*
 * Copyright 2013-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.seqera.docker.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker CLI wrapper for container operations.
 * <p>
 * This library provides a simple interface to Docker operations using the docker CLI
 * directly via {@link ProcessBuilder}. This approach is preferred over the docker-java
 * library because:
 * <ul>
 *   <li>Works reliably on macOS Docker Desktop for local development and testing</li>
 *   <li>No native library dependencies or socket configuration issues</li>
 *   <li>Simpler debugging - commands can be copied from logs and run manually</li>
 *   <li>Consistent behavior across Linux, macOS, and Windows platforms</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * DockerCli docker = new DockerCli();
 *
 * // Run a container (blocks until completion by default)
 * ContainerConfig config = new ContainerConfig()
 *     .name("my-container")
 *     .image("alpine:latest")
 *     .command(List.of("echo", "Hello"));
 * docker.run(config);
 *
 * // Check container state
 * ContainerState state = docker.inspect("my-container");
 * if (state.isExited() && state.isSucceeded()) {
 *     String logs = docker.logs("my-container");
 * }
 *
 * // Cleanup
 * docker.rm("my-container");
 * }</pre>
 *
 * <h2>Detached Mode</h2>
 * By default, containers run in foreground mode (detach=false), blocking until
 * the container exits. Use {@link ContainerConfig#detach(boolean)} with {@code true}
 * for background execution, then poll with {@link #inspect(String)} for completion.
 *
 * @author Paolo Di Tommaso
 * @see ContainerConfig
 * @see ContainerState
 */
public class DockerCli {

    private static final Logger log = LoggerFactory.getLogger(DockerCli.class);

    /**
     * Run a container with the given configuration.
     * <p>
     * By default (detach=false), this method blocks until the container exits.
     * When detach=true, the container runs in background and this method returns
     * immediately with the container ID.
     *
     * @param config container configuration specifying image, command, environment, etc.
     * @return container ID if detached, or command output if not detached
     * @throws DockerCliException if Docker fails to start the container (only in detached mode)
     * @see ContainerConfig
     */
    public String run(ContainerConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");

        if (config.isDetach()) {
            cmd.add("--detach");
        }

        if (config.getName() != null) {
            cmd.add("--name");
            cmd.add(config.getName());
        }

        if (config.isPrivileged()) {
            cmd.add("--privileged");
        }

        for (String cap : config.getCapAdd()) {
            cmd.add("--cap-add");
            cmd.add(cap);
        }

        for (String device : config.getDevices()) {
            cmd.add("--device");
            cmd.add(device);
        }

        if (config.getPlatform() != null && !config.getPlatform().isEmpty()) {
            cmd.add("--platform");
            cmd.add(config.getPlatform());
        }

        if (config.getWorkDir() != null) {
            cmd.add("-w");
            cmd.add(config.getWorkDir());
        }

        for (String volume : config.getVolumes()) {
            cmd.add("-v");
            cmd.add(volume);
        }

        for (Map.Entry<String, String> entry : config.getEnvironment().entrySet()) {
            cmd.add("-e");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }

        cmd.add(config.getImage());

        if (config.getCommand() != null) {
            cmd.addAll(config.getCommand());
        }

        log.debug("Docker run command: {}", String.join(" ", cmd));

        ExecResult result = exec(cmd);
        // When detached, non-zero means docker failed to start the container
        // When not detached, the exit code is the container's exit code (expected)
        if (config.isDetach() && result.exitCode != 0) {
            throw new DockerCliException("Failed to run container: " + result.output.trim(), result.exitCode);
        }

        return result.output.trim();
    }

    /**
     * Get container state using docker inspect.
     * <p>
     * Returns the current state of the container including its status
     * (running, exited, created, etc.) and exit code if applicable.
     *
     * @param containerName container name or ID
     * @return container state, or a state with status "unknown" if container not found
     * @see ContainerState
     */
    public ContainerState inspect(String containerName) {
        List<String> cmd = List.of("docker", "inspect", "--format", "{{.State.Status}},{{.State.ExitCode}}", containerName);

        ExecResult result = exec(cmd);
        if (result.exitCode != 0) {
            log.debug("Container not found or inspect failed: {}", containerName);
            return new ContainerState("unknown", null);
        }

        return ContainerState.parse(result.output.trim());
    }

    /**
     * Get container logs (stdout and stderr combined).
     * <p>
     * Retrieves all logs from the container. For running containers, returns
     * logs up to the current point. For exited containers, returns complete logs.
     *
     * @param containerName container name or ID
     * @return container logs (stdout + stderr combined), or empty string on error
     */
    public String logs(String containerName) {
        List<String> cmd = List.of("docker", "logs", containerName);

        ExecResult result = exec(cmd);
        return result.output;
    }

    /**
     * Stop a running container with specified timeout.
     * <p>
     * Sends SIGTERM to the container, then SIGKILL after the timeout.
     * This method is idempotent - calling it on an already stopped container is safe.
     *
     * @param containerName container name or ID
     * @param timeoutSeconds seconds to wait before killing the container
     */
    public void stop(String containerName, int timeoutSeconds) {
        List<String> cmd = List.of("docker", "stop", "-t", String.valueOf(timeoutSeconds), containerName);
        exec(cmd); // Ignore exit code - container might already be stopped
    }

    /**
     * Stop a running container with default timeout (5 seconds).
     *
     * @param containerName container name or ID
     * @see #stop(String, int)
     */
    public void stop(String containerName) {
        stop(containerName, 5);
    }

    /**
     * Remove a container.
     * <p>
     * Removes the container and its associated resources. Use force=true
     * to remove running containers (they will be killed first).
     * This method is idempotent - calling it on a non-existent container is safe.
     *
     * @param containerName container name or ID
     * @param force if true, force removal even if container is running
     */
    public void rm(String containerName, boolean force) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("rm");
        if (force) {
            cmd.add("-f");
        }
        cmd.add(containerName);

        exec(cmd); // Ignore exit code - container might not exist
    }

    /**
     * Remove a stopped container.
     *
     * @param containerName container name or ID
     * @see #rm(String, boolean)
     */
    public void rm(String containerName) {
        rm(containerName, false);
    }

    /**
     * Check if Docker is available on this system.
     * <p>
     * Verifies that the docker CLI is installed and the Docker daemon is running
     * by executing {@code docker info}.
     *
     * @return true if docker CLI is available and daemon is running, false otherwise
     */
    public static boolean isAvailable() {
        try {
            ProcessBuilder builder = new ProcessBuilder("docker", "info");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            consumeOutput(process.getInputStream(), new StringBuilder());
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Execute a command and return the result.
     */
    private ExecResult exec(List<String> cmd) {
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            StringBuilder output = new StringBuilder();
            Thread consumer = consumeOutput(process.getInputStream(), output);

            int exitCode = process.waitFor();
            consumer.join();

            return new ExecResult(exitCode, output.toString());
        } catch (IOException | InterruptedException e) {
            log.error("Failed to execute command: {}", cmd, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ExecResult(-1, e.getMessage());
        }
    }

    /**
     * Consume process output in a background thread to prevent deadlock
     * when the process output buffer fills up.
     */
    private static Thread consumeOutput(InputStream inputStream, StringBuilder output) {
        Thread consumer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (IOException e) {
                log.debug("Error consuming process output: {}", e.getMessage());
            }
        });
        consumer.start();
        return consumer;
    }

    private record ExecResult(int exitCode, String output) {}
}
