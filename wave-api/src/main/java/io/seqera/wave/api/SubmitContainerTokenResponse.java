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

import java.time.Instant;
import java.util.Objects;


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
     * The time instant when the container token is going to expire.
     * This attribute is only available when {@link #freeze} is {@code false}
     */
    public Instant expiration;

    /**
     * The source container image that originated this request
     */
    public String containerImage;

    /**
     * The ID of the build associated with this request or null of the image already exists.
     * Version v1alpha2 as later.
     */
    public String buildId;

    /**
     * Whenever it's a cached build image. Only supported by API version v1alpha2  
     */
    public Boolean cached;

    /**
     * When the result is a freeze container. Version v1alpha2 as later.
     */
    public Boolean freeze;

    public Boolean mirror;

    public SubmitContainerTokenResponse() { }

    public SubmitContainerTokenResponse(String token, String target, Instant expiration, String containerImage, String buildId, Boolean cached, Boolean freeze, Boolean mirror) {
        this.containerToken = token;
        this.targetImage = target;
        this.expiration = expiration;
        this.containerImage = containerImage;
        this.buildId = buildId;
        this.cached = cached;
        this.freeze = freeze;
        this.mirror = mirror;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (object == null || getClass() != object.getClass()) return false;
        SubmitContainerTokenResponse that = (SubmitContainerTokenResponse) object;
        return Objects.equals(containerToken, that.containerToken)
                && Objects.equals(targetImage, that.targetImage)
                && Objects.equals(expiration, that.expiration)
                && Objects.equals(containerImage, that.containerImage)
                && Objects.equals(buildId, that.buildId)
                && Objects.equals(cached, that.cached)
                && Objects.equals(freeze, that.freeze)
                && Objects.equals(mirror, that.mirror) ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(containerToken, targetImage, expiration, containerImage, buildId, cached, freeze, mirror);
    }

    @Override
    public String toString() {
        return "SubmitContainerTokenResponse{" +
                "containerToken='" + containerToken + '\'' +
                ", targetImage='" + targetImage + '\'' +
                ", expiration=" + expiration +
                ", containerImage='" + containerImage + '\'' +
                ", buildId='" + buildId + '\'' +
                ", cached=" + cached +
                ", freeze=" + freeze +
                ", mirror=" + mirror +
                '}';
    }
}
