/*
 * Copyright 2025, Seqera Labs
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
 */
package io.seqera.lib.hash;

/**
 * Interface for computing hashes from various input types.
 * Implementations accumulate data and produce a final hash value.
 *
 * @author Paolo Di Tommaso
 */
public interface Hasher {

    /**
     * Add a string to the hash computation.
     *
     * @param value the string value (null-safe)
     * @return this hasher for method chaining
     */
    Hasher putString(String value);

    /**
     * Add a boolean to the hash computation.
     *
     * @param value the boolean value
     * @return this hasher for method chaining
     */
    Hasher putBoolean(boolean value);

    /**
     * Add an integer to the hash computation.
     *
     * @param value the integer value
     * @return this hasher for method chaining
     */
    Hasher putInt(int value);

    /**
     * Add a separator to the hash computation.
     * Used to delimit fields and prevent collisions.
     *
     * @return this hasher for method chaining
     */
    Hasher putSeparator();

    /**
     * Compute the final hash as a long value.
     *
     * @return the computed hash
     */
    long toLong();
}
