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

package io.seqera.serde.gson;

import io.seqera.serde.Encodable;

/**
 * Marker interface for objects that are serializable using Gson.
 * <p>
 * This interface extends {@link Encodable} and serves as a type-safe marker
 * to indicate that implementing classes are designed for Gson serialization.
 * It does not impose any behavioral requirements on implementing classes.
 * <p>
 * Usage example:
 * <pre>{@code
 * public class MyData implements GsonSerializable {
 *     private String name;
 *     private int value;
 *     // ...
 * }
 * }</pre>
 *
 * @author Paolo Di Tommaso
 */
public interface GsonSerializable extends Encodable {
}
