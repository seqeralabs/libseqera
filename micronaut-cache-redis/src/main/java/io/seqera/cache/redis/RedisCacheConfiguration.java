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

    private EncryptionConfiguration encryption = new EncryptionConfiguration();

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

    /**
     * @return The encryption configuration for this cache
     */
    public EncryptionConfiguration getEncryption() {
        return encryption;
    }

    /**
     * @param encryption The encryption configuration
     */
    public void setEncryption(EncryptionConfiguration encryption) {
        this.encryption = encryption;
    }

    /**
     * Nested configuration for cache value encryption.
     * Configured under {@code redis.caches.<name>.encryption}.
     */
    @ConfigurationProperties("encryption")
    public static class EncryptionConfiguration {

        private boolean enabled = false;
        private String secret;

        /**
         * @return Whether encryption is enabled for this cache (default: false)
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * @param enabled Whether to enable encryption
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * @return The secret passphrase used to derive the AES-256 encryption key
         */
        public String getSecret() {
            return secret;
        }

        /**
         * @param secret The secret passphrase
         */
        public void setSecret(String secret) {
            this.secret = secret;
        }
    }
}
