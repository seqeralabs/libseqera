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

import io.micronaut.cache.SyncCache;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.ApplicationConfiguration;

/**
 * Default configuration for Redis caches.
 * Provides default settings that can be overridden by individual cache configurations.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@ConfigurationProperties(RedisSetting.REDIS_CACHE)
@Requires(classes = SyncCache.class)
public class DefaultRedisCacheConfiguration extends AbstractRedisCacheConfiguration {

    /**
     * Constructor.
     *
     * @param applicationConfiguration the application configuration
     */
    public DefaultRedisCacheConfiguration(ApplicationConfiguration applicationConfiguration) {
        super(applicationConfiguration);
    }
}
