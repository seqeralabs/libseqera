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

package io.seqera.cloudinfo.client;

import java.util.List;

/**
 * Exception thrown when a Cloudinfo API request fails.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CloudInfoException extends RuntimeException {

    private final int statusCode;

    /**
     * Accepted feature tokens reported by the server on a /families 400 response,
     * or null when the failure carried no such list.
     */
    private final List<String> validCapabilities;

    public CloudInfoException(String message, int statusCode) {
        this(message, statusCode, null);
    }

    public CloudInfoException(String message, int statusCode, List<String> validCapabilities) {
        super(message);
        this.statusCode = statusCode;
        this.validCapabilities = validCapabilities == null ? null : List.copyOf(validCapabilities);
    }

    public CloudInfoException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.validCapabilities = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * The accepted feature tokens reported on a /families 400 rejection.
     *
     * @return an immutable list of valid tokens, or null when none was included
     */
    public List<String> getValidCapabilities() {
        return validCapabilities;
    }
}
