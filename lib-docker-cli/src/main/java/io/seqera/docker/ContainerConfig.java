/*
 * Copyright 2025, Seqera Labs
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
package io.seqera.docker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for running a Docker container.
 * <p>
 * Uses a fluent builder pattern for easy configuration. All setter methods
 * return {@code this} for method chaining.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * ContainerConfig config = new ContainerConfig()
 *     .name("my-container")
 *     .image("alpine:latest")
 *     .command(List.of("echo", "Hello"))
 *     .env("MY_VAR", "value")
 *     .platform("linux/amd64");
 * }</pre>
 *
 * <h2>FUSE Support</h2>
 * For containers requiring FUSE filesystem support (e.g., Fusion), use
 * {@link #withFuseSupport()} which configures privileged mode, SYS_ADMIN
 * capability, and /dev/fuse device access:
 * <pre>{@code
 * ContainerConfig config = new ContainerConfig()
 *     .name("fusion-job")
 *     .image("my-image:latest")
 *     .withFuseSupport();
 * }</pre>
 *
 * @author Paolo Di Tommaso
 * @see DockerCli#run(ContainerConfig)
 */
public class ContainerConfig {

    private String name;
    private String image;
    private List<String> command;
    private Map<String, String> environment;
    private String workDir;
    private List<String> volumes;
    private String platform;
    private boolean privileged;
    private List<String> capAdd;
    private List<String> devices;
    private boolean detach = false;

    /**
     * Creates a new empty container configuration.
     */
    public ContainerConfig() {
        this.environment = new LinkedHashMap<>();
        this.volumes = new ArrayList<>();
        this.capAdd = new ArrayList<>();
        this.devices = new ArrayList<>();
    }

    /**
     * Set the container name.
     * <p>
     * The name must be unique among running containers. It can be used
     * to reference the container in subsequent operations.
     *
     * @param name container name (must match {@code [a-zA-Z0-9][a-zA-Z0-9_.-]+})
     * @return this configuration for chaining
     */
    public ContainerConfig name(String name) {
        this.name = name;
        return this;
    }

    /**
     * Set the Docker image to run.
     *
     * @param image image name with optional tag (e.g., "alpine:latest", "ubuntu:22.04")
     * @return this configuration for chaining
     */
    public ContainerConfig image(String image) {
        this.image = image;
        return this;
    }

    /**
     * Set the command to run in the container.
     * <p>
     * This overrides the default CMD specified in the image.
     *
     * @param command command and arguments as a list
     * @return this configuration for chaining
     */
    public ContainerConfig command(List<String> command) {
        this.command = command;
        return this;
    }

    /**
     * Add a single environment variable.
     *
     * @param key environment variable name
     * @param value environment variable value
     * @return this configuration for chaining
     */
    public ContainerConfig env(String key, String value) {
        this.environment.put(key, value);
        return this;
    }

    /**
     * Add multiple environment variables.
     *
     * @param env map of environment variables
     * @return this configuration for chaining
     */
    public ContainerConfig env(Map<String, String> env) {
        if (env != null) {
            this.environment.putAll(env);
        }
        return this;
    }

    /**
     * Set the working directory inside the container.
     *
     * @param workDir absolute path to the working directory
     * @return this configuration for chaining
     */
    public ContainerConfig workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    /**
     * Add a volume mount.
     *
     * @param volume volume specification (e.g., "/host/path:/container/path:ro")
     * @return this configuration for chaining
     */
    public ContainerConfig volume(String volume) {
        this.volumes.add(volume);
        return this;
    }

    /**
     * Set the platform for multi-architecture images.
     *
     * @param platform platform specification (e.g., "linux/amd64", "linux/arm64")
     * @return this configuration for chaining
     */
    public ContainerConfig platform(String platform) {
        this.platform = platform;
        return this;
    }

    /**
     * Enable or disable privileged mode.
     * <p>
     * Privileged containers have full access to host devices and
     * kernel capabilities. Use with caution.
     *
     * @param privileged true to enable privileged mode
     * @return this configuration for chaining
     */
    public ContainerConfig privileged(boolean privileged) {
        this.privileged = privileged;
        return this;
    }

    /**
     * Add a Linux capability.
     *
     * @param capability capability name (e.g., "SYS_ADMIN", "NET_ADMIN")
     * @return this configuration for chaining
     */
    public ContainerConfig capAdd(String capability) {
        this.capAdd.add(capability);
        return this;
    }

    /**
     * Add a device mapping.
     *
     * @param device device specification (e.g., "/dev/fuse:/dev/fuse:rwm")
     * @return this configuration for chaining
     */
    public ContainerConfig device(String device) {
        this.devices.add(device);
        return this;
    }

    /**
     * Enable or disable detached (background) mode.
     * <p>
     * When false (default), {@link DockerCli#run(ContainerConfig)} blocks until
     * the container exits. When true, the container runs in background and
     * run() returns immediately with the container ID.
     *
     * @param detach true for background execution, false to wait for completion
     * @return this configuration for chaining
     */
    public ContainerConfig detach(boolean detach) {
        this.detach = detach;
        return this;
    }

    /**
     * Configure FUSE support for Fusion filesystem.
     * <p>
     * This enables:
     * <ul>
     *   <li>Privileged mode</li>
     *   <li>SYS_ADMIN capability</li>
     *   <li>/dev/fuse device access</li>
     * </ul>
     * Required for containers using FUSE-based filesystems like Fusion.
     *
     * @return this configuration for chaining
     */
    public ContainerConfig withFuseSupport() {
        this.privileged = true;
        this.capAdd.add("SYS_ADMIN");
        this.devices.add("/dev/fuse:/dev/fuse:rwm");
        return this;
    }

    // Getters

    /** @return the container name, or null if not set */
    public String getName() { return name; }

    /** @return the Docker image to run */
    public String getImage() { return image; }

    /** @return the command to execute, or null for image default */
    public List<String> getCommand() { return command; }

    /** @return environment variables map (never null) */
    public Map<String, String> getEnvironment() { return environment; }

    /** @return the working directory, or null if not set */
    public String getWorkDir() { return workDir; }

    /** @return volume mounts list (never null) */
    public List<String> getVolumes() { return volumes; }

    /** @return the platform specification, or null if not set */
    public String getPlatform() { return platform; }

    /** @return true if privileged mode is enabled */
    public boolean isPrivileged() { return privileged; }

    /** @return added capabilities list (never null) */
    public List<String> getCapAdd() { return capAdd; }

    /** @return device mappings list (never null) */
    public List<String> getDevices() { return devices; }

    /** @return true if detached (background) mode is enabled */
    public boolean isDetach() { return detach; }
}
