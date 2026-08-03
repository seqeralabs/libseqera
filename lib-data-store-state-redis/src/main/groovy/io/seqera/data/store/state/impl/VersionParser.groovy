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

package io.seqera.data.store.state.impl

import groovy.transform.CompileStatic

/**
 * The single version parser shared by every reader of the {@code {"@v":N} frame,
 * so the version a stored form carries is the same wherever it is inspected.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
final class VersionParser {

    private VersionParser() {}

    /**
     * Parse the digits captured from a leading {@code {"@v":N} frame into the version
     * they carry: their numeric value — leading zeros are insignificant — or {@code null}
     * when they do not fit a signed 64-bit long, in which case callers count the entry
     * as version {@code 0}, the same as an unframed one. The compare-and-swap script in
     * {@link RedisStateProvider} mirrors the same semantics server-side.
     */
    static Long parseVersion(String digits) {
        try {
            return Long.valueOf(digits)
        }
        catch( NumberFormatException ignored ) {
            return null
        }
    }

}
