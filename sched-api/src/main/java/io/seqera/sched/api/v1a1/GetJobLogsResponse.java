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
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class GetJobLogsResponse {
    public String stdout;
    public String stderr;

    public GetJobLogsResponse withStdout(String stdout) {
        this.stdout = stdout;
        return this;
    }

    public GetJobLogsResponse withStderr(String stderr) {
        this.stderr = stderr;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GetJobLogsResponse that = (GetJobLogsResponse) o;
        return Objects.equals(stdout, that.stdout) && Objects.equals(stderr, that.stderr);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stdout, stderr);
    }

    @Override
    public String toString() {
        return "GetJobLogsResponse{" +
                "stdout='" + stdout + '\'' +
                ", stderr='" + stderr + '\'' +
                '}';
    }
}
