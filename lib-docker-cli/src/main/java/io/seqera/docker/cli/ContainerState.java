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

/**
 * Represents the state of a Docker container.
 * <p>
 * This record captures the container status and exit code as reported by
 * {@code docker inspect}. It provides convenience methods to check common
 * states like running, exited, or pending.
 *
 * <h2>Status Values</h2>
 * Docker containers can have the following status values:
 * <ul>
 *   <li>{@code created} - Container created but not started</li>
 *   <li>{@code running} - Container is currently running</li>
 *   <li>{@code paused} - Container execution is paused</li>
 *   <li>{@code restarting} - Container is being restarted</li>
 *   <li>{@code exited} - Container has stopped</li>
 *   <li>{@code dead} - Container is in a dead state (daemon error)</li>
 *   <li>{@code unknown} - Container not found or inspect failed</li>
 * </ul>
 *
 * <h2>Exit Codes</h2>
 * Common exit codes:
 * <ul>
 *   <li>{@code 0} - Success</li>
 *   <li>{@code 1} - General error</li>
 *   <li>{@code 137} - SIGKILL (128 + 9)</li>
 *   <li>{@code 143} - SIGTERM (128 + 15)</li>
 * </ul>
 *
 * @param status the container status (e.g., "running", "exited")
 * @param exitCode the exit code, or null if container hasn't exited
 * @author Paolo Di Tommaso
 * @see DockerCli#inspect(String)
 */
public record ContainerState(String status, Integer exitCode) {

    /**
     * Check if the container is currently running.
     *
     * @return true if status is "running"
     */
    public boolean isRunning() {
        return "running".equals(status);
    }

    /**
     * Check if the container has exited (stopped).
     *
     * @return true if status is "exited"
     */
    public boolean isExited() {
        return "exited".equals(status);
    }

    /**
     * Check if the container is in a pending state.
     * <p>
     * A container is pending if it has been created but not yet running,
     * or if it is paused.
     *
     * @return true if status is "created" or "paused"
     */
    public boolean isPending() {
        return "created".equals(status) || "paused".equals(status);
    }

    /**
     * Check if the container exited successfully.
     * <p>
     * A container is considered successful if it has exited with exit code 0.
     *
     * @return true if container exited with exit code 0
     */
    public boolean isSucceeded() {
        return isExited() && exitCode != null && exitCode == 0;
    }

    /**
     * Check if the container failed.
     * <p>
     * A container is considered failed if it has exited with a non-zero
     * exit code, or if the exit code is unknown (null).
     *
     * @return true if container exited with non-zero exit code
     */
    public boolean isFailed() {
        return isExited() && (exitCode == null || exitCode != 0);
    }

    /**
     * Parse container state from docker inspect output.
     * <p>
     * Expects format: {@code status,exitCode} (e.g., "exited,0" or "running,0").
     * The exit code may be empty for running containers.
     *
     * @param result the output from {@code docker inspect --format '{{.State.Status}},{{.State.ExitCode}}'}
     * @return parsed container state, or state with "unknown" status if input is null/empty
     */
    public static ContainerState parse(String result) {
        if (result == null || result.isEmpty()) {
            return new ContainerState("unknown", null);
        }
        String[] parts = result.split(",");
        String status = parts[0];
        Integer exitCode = parts.length > 1 && !parts[1].isEmpty() ? Integer.valueOf(parts[1]) : null;
        return new ContainerState(status, exitCode);
    }
}
