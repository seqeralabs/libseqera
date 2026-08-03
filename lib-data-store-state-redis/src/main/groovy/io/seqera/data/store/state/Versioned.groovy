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

package io.seqera.data.store.state

/**
 * Contract for values that carry their own optimistic-concurrency version, in the style
 * of a JPA {@code @Version} field: the version is read together with the value, travels
 * through the caller's domain transitions, and acts as the write witness for
 * {@link StateStore#replaceIf} — a conditional write lands only when the stored version
 * still equals the one the caller read, and is persisted with the version incremented.
 *
 * <p>A value deserialized from an entry written before versioning existed reports
 * version {@code 0} (the field is simply absent from the stored form), so legacy entries
 * are adopted transparently by their first successful conditional write.
 *
 * <p>The version is expected to change on <em>every</em> write: unconditional
 * {@code put} writes should be reserved for entry creation, otherwise a concurrent
 * conditional write cannot detect them reliably.
 *
 * @param <T> the self type, so {@link #withVersion} preserves the concrete type
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
interface Versioned<T> {

    /**
     * @return The optimistic-concurrency version of this value; {@code 0} for a value
     *         that was never written through a versioned conditional write
     */
    long version()

    /**
     * Create a copy of this value carrying the specified version.
     *
     * @param version The version the copy should report
     * @return A copy of this value identical except for the version
     */
    T withVersion(long version)

}
