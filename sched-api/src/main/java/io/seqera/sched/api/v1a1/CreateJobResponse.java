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
 * Moder a response for a {@link CreateJobRequest}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CreateJobResponse {

    public String jobId;
    public String error;

    public CreateJobResponse withJobId(String jobId) {
        this.jobId = jobId;
        return this;
    }

    public CreateJobResponse withError(String error) {
        this.error = error;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CreateJobResponse that = (CreateJobResponse) o;
        return Objects.equals(jobId, that.jobId) && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, error);
    }

    @Override
    public String toString() {
        return "CreateJobResponse{" +
                "jobId='" + jobId + '\'' +
                ", error='" + error + '\'' +
                '}';
    }
}
