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
 * Model the response for a cancel jobs request
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CancelJobResponse {

    /**
     * IDs of the jobs to be deleted
     */
    JobState jobState;

    public CancelJobResponse withJobState(JobState jobState) {
        this.jobState = jobState;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CancelJobResponse that = (CancelJobResponse) o;
        return Objects.equals(jobState, that.jobState);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(jobState);
    }

    @Override
    public String toString() {
        return "CancelJobResponse{" +
                "jobState=" + jobState +
                '}';
    }
}
