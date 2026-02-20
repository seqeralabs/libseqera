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
 *
 */

package io.seqera.data.command;

/**
 * A command represents a unit of work to be executed asynchronously.
 * Commands can take arbitrary time (from milliseconds to days) and survive system restarts.
 *
 * @param <P> The type of the command parameters
 */
public interface Command<P> {

    /**
     * Unique identifier for this command (typically a TSID).
     */
    String id();

    /**
     * The command type, used to route to the appropriate handler.
     */
    String type();

    /**
     * The typed parameters for this command.
     */
    P params();
}
