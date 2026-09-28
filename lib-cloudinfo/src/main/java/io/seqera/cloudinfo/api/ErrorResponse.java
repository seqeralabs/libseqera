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
 * Cloudinfo API error body. The /families endpoint returns it with HTTP 400 when
 * a requested feature token is unknown or not lowercase; validCapabilities then
 * lists the accepted tokens. Other endpoints answer with an RFC 7807 problem
 * ({type, title, status, detail}), of which status and detail are kept. Fields
 * absent from the body are null.
 */
public class ErrorResponse {

    private String error;
    private List<String> validCapabilities;
    /** RFC 7807 problem status, e.g. 404 when /storage has no prices for the region. */
    private Integer status;
    /** RFC 7807 problem detail, e.g. which path parameter failed validation. */
    private String detail;

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

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ErrorResponse that = (ErrorResponse) o;
        return Objects.equals(error, that.error)
                && Objects.equals(validCapabilities, that.validCapabilities)
                && Objects.equals(status, that.status)
                && Objects.equals(detail, that.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(error, validCapabilities, status, detail);
    }

    @Override
    public String toString() {
        return "ErrorResponse[error=" + error + ", validCapabilities=" + validCapabilities +
                ", status=" + status + ", detail=" + detail + "]";
    }
}
