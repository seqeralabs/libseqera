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

package io.seqera.nodeid;

/**
 * Unique node ordinal assigned to the running instance.
 *
 * <p>The ordinal is intended to seed collision-free distributed ID generators
 * (e.g. a TSID node ID) across the replicas of a horizontally scaled service.
 * Implementations differ in how the ordinal is coordinated: a local single-instance
 * implementation always returns {@code 0}, while a Redis-backed implementation
 * atomically claims a distinct ordinal per live replica.
 *
 * @author Paolo Di Tommaso
 */
public interface NodeId {

    /**
     * The node ordinal assigned to this instance.
     *
     * @return a value in the range {@code [0, capacity())}
     */
    int value();

    /**
     * The size of the node-ID space, i.e. the number of distinct ordinals that can be
     * assigned. Consumers should align this with their ID generator's node-bit allocation.
     *
     * @return the node-ID space size
     */
    int capacity();
}
