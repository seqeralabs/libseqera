/*
 * Copyright 2026, Seqera Labs
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

package io.seqera.cloudinfo.api;

import java.util.List;
import java.util.Objects;

/**
 * Model for a Cloudinfo API error body.
 *
 * <p>The {@code /families} endpoint returns this shape with HTTP 400 when a
 * requested {@code features} token is unknown or not lowercase, e.g.
 * <pre>{@code {"error": "unknown feature \"bogus\"", "validCapabilities": ["arm", "gpu", "ssd"]}}</pre>
 * where {@code validCapabilities} is the set of accepted feature tokens for the
 * provider. Endpoints that do not produce this shape simply leave the fields
 * {@code null} after decoding.
 */
public class ErrorResponse {

    private String error;
    private List<String> validCapabilities;

    public ErrorResponse() {
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getValidCapabilities() {
        return validCapabilities;
    }

    public void setValidCapabilities(List<String> validCapabilities) {
        this.validCapabilities = validCapabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ErrorResponse that = (ErrorResponse) o;
        return Objects.equals(error, that.error)
                && Objects.equals(validCapabilities, that.validCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(error, validCapabilities);
    }

    @Override
    public String toString() {
        return "ErrorResponse[error=" + error + ", validCapabilities=" + validCapabilities + "]";
    }
}
