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
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.ApplicationConfiguration;

/**
 * Configuration for individual Redis caches.
 * Each named cache under 'redis.caches' creates an instance of this configuration.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EachProperty(RedisSetting.REDIS_CACHES)
@Requires(classes = SyncCache.class)
public class RedisCacheConfiguration extends AbstractRedisCacheConfiguration {

    protected final String cacheName;

    /**
     * Constructor.
     *
     * @param cacheName                the name of the cache
     * @param applicationConfiguration the application configuration
     */
    public RedisCacheConfiguration(@Parameter String cacheName, ApplicationConfiguration applicationConfiguration) {
        super(applicationConfiguration);
        this.cacheName = cacheName;
    }

    /**
     * @return The name of the cache
     */
    public String getCacheName() {
        return cacheName;
    }
}
