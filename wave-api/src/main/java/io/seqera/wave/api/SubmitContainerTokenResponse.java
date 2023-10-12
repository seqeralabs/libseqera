/*
 * Copyright 2023, Seqera Labs
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

import java.time.Instant;


/**
 * Model a response for an augmented container
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class SubmitContainerTokenResponse {

    /**
     * A unique authorization token assigned to this request
     */
    public String containerToken;

    /**
     * The fully qualified wave container name to be used
     */
    public String targetImage;

    /**
     * The time instant when the container token is going to expire
     */
    public Instant expiration;

    /**
     * The source container image that originated this request
     */
    public String containerImage;

    /**
     * The ID of the build associated with this request or null of the image already exists
     */
    public String buildId;

    public SubmitContainerTokenResponse() { }

    public SubmitContainerTokenResponse(String token, String target, Instant expiration, String containerImage, String buildId) {
        this.containerToken = token;
        this.targetImage = target;
        this.expiration = expiration;
        this.containerImage = containerImage;
        this.buildId = buildId;
    }
}
