/*
 * Copyright 2024, Seqera Labs
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

package io.seqera.wave.api;

import java.time.Duration;
import java.time.Instant;


/**
 * Build status response
 *
 * @author Munish Chouhan <munish.chouhan@seqera.io>
 */
public class BuildStatusResponse {
    public enum Status { PENDING, COMPLETED }

    /** Build Id */
    public String id;

    /** Status of image build */
    public Status status;

    /** Build start time */
    public Instant startTime;

    /** Duration to complete build */
    public Duration duration;

    /** Build success status */
    public Boolean succeeded;

    public BuildStatusResponse() {}

    public BuildStatusResponse(String id, Status status, Instant startTime, Duration duration, Boolean succeeded) {
        this.id = id;
        this.status = status;
        this.startTime = startTime;
        this.duration = duration;
        this.succeeded = succeeded;
    }
}
