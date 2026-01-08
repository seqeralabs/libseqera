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
package io.seqera.cache.redis;

/**
 * Constants for Redis configuration property names.
 * Mirrors the official micronaut-redis configuration for drop-in compatibility.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface RedisSetting {

    /**
     * Configuration prefix for Redis settings.
     */
    String PREFIX = "redis";

    /**
     * Configuration property for default cache settings.
     */
    String REDIS_CACHE = PREFIX + ".cache";

    /**
     * Configuration property for individual cache configurations.
     */
    String REDIS_CACHES = PREFIX + ".caches";
}
