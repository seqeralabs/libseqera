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
 *
 */

package io.seqera.sched.api.v1a1;

import java.util.Objects;

/**
 * Model a Job state
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class JobState {

    public enum Status {
        SUBMITTED,
        RUNNING,
        DONE,
        FAILED,
        CANCELLED,
        UNKNOWN
    }

    public String id;
    public Status status;
    public Integer exitCode;
    public String logs;

    public JobState withId(String id) {
        this.id = id;
        return this;
    }

    public JobState withStatus(Status status) {
        this.status = status;
        return this;
    }

    public JobState withExitCode(Integer exitCode) {
        this.exitCode = exitCode;
        return this;
    }

    public JobState withLogs(String exitMessage) {
        this.logs = exitMessage;
        return this;
    }

    @Override
    public String toString() {
        return "JobState [id=" + id + ", status=" + status + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JobState jobState = (JobState) o;
        return Objects.equals(id, jobState.id) && status == jobState.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, status);
    }

    public boolean isRunningOrTerminated() {
        return isRunning() || isTerminated();
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isTerminated() {
        return status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED;
    }
}
