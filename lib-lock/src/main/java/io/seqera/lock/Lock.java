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

package io.seqera.lock;

/**
 * Represents an acquired distributed lock.
 *
 * Implements {@link AutoCloseable} for use with try-with-resources.
 *
 * @author Paolo Di Tommaso
 */
public interface Lock extends AutoCloseable {

    /**
     * Release the lock.
     *
     * @return {@code true} if the lock was released successfully,
     *         {@code false} if the lock was held by another instance
     */
    boolean release();

    /**
     * Release the lock via AutoCloseable.
     */
    @Override
    default void close() {
        release();
    }
}
