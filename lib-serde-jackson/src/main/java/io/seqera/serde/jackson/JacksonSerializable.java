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

package io.seqera.serde.jackson;

import io.seqera.serde.Encodable;

/**
 * Marker interface for Jackson serializable objects.
 *
 * <p>This interface can be used to mark classes that are intended to be serialized
 * and deserialized using the Jackson JSON library. It serves as a type marker and
 * documentation aid but does not impose any behavioral requirements.</p>
 *
 * <p>Extends {@link Encodable} to allow Jackson-serializable objects to be used
 * in generic encoding contexts without coupling to the Jackson library.</p>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface JacksonSerializable extends Encodable {
}
