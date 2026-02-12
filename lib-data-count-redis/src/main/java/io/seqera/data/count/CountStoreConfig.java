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

package io.seqera.data.count;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * Configuration for named count stores.
 *
 * Supports multiple named configurations via:
 * <pre>
 * seqera:
 *   count:
 *     tasks:
 *       prefix: task-counter
 *     builds:
 *       prefix: build-counter
 * </pre>
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EachProperty("seqera.count")
public class CountStoreConfig {

    private final String name;
    private String prefix;

    public CountStoreConfig(@Parameter String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix != null ? prefix : name;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public String toString() {
        return "CountStoreConfig{name='" + name + "', prefix='" + getPrefix() + "'}";
    }
}
