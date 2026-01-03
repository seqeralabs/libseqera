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
 * Exception thrown when a Docker CLI command fails.
 * <p>
 * This exception is thrown when Docker operations fail, such as when
 * {@link DockerCli#run(ContainerConfig)} cannot start a container in
 * detached mode. The exit code from the docker command is available
 * via {@link #getExitCode()}.
 *
 * <h2>Common Exit Codes</h2>
 * <ul>
 *   <li>{@code 1} - Generic error (e.g., invalid arguments)</li>
 *   <li>{@code 125} - Docker daemon error</li>
 *   <li>{@code 126} - Command cannot be invoked</li>
 *   <li>{@code 127} - Command not found</li>
 * </ul>
 *
 * @author Paolo Di Tommaso
 * @see DockerCli
 */
public class DockerCliException extends RuntimeException {

    private final int exitCode;

    /**
     * Create a new DockerCliException.
     *
     * @param message error message describing the failure
     * @param exitCode exit code from the docker command
     */
    public DockerCliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    /**
     * Create a new DockerCliException with a cause.
     *
     * @param message error message describing the failure
     * @param exitCode exit code from the docker command
     * @param cause the underlying cause of the exception
     */
    public DockerCliException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /**
     * Get the exit code from the failed docker command.
     *
     * @return the process exit code
     */
    public int getExitCode() {
        return exitCode;
    }
}
