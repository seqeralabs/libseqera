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

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;

import io.micronaut.core.serialize.ObjectSerializer;
import io.micronaut.runtime.ApplicationConfiguration;

/**
 * Abstract base configuration for Redis caches.
 * Provides common configuration options for cache behavior.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public abstract class AbstractRedisCacheConfiguration {

    protected Class<ObjectSerializer> keySerializer;
    protected Class<ObjectSerializer> valueSerializer;
    protected Charset charset;
    protected Duration expireAfterWrite;
    protected Duration expireAfterAccess;
    protected String expirationAfterWritePolicy;
    protected Long invalidateScanCount = 100L;

    /**
     * Constructor.
     *
     * @param applicationConfiguration the application configuration
     */
    public AbstractRedisCacheConfiguration(ApplicationConfiguration applicationConfiguration) {
        this.charset = applicationConfiguration.getDefaultCharset();
    }

    /**
     * @return The {@link ObjectSerializer} type to use for serializing values.
     */
    public Optional<Class<ObjectSerializer>> getValueSerializer() {
        return Optional.ofNullable(valueSerializer);
    }

    /**
     * Sets the value serializer class.
     *
     * @param valueSerializer the serializer class
     */
    public void setValueSerializer(Class<ObjectSerializer> valueSerializer) {
        this.valueSerializer = valueSerializer;
    }

    /**
     * The {@link ObjectSerializer} to use for serializing keys.
     *
     * @return The optional {@link ObjectSerializer} class
     */
    public Optional<Class<ObjectSerializer>> getKeySerializer() {
        return Optional.ofNullable(keySerializer);
    }

    /**
     * Sets the key serializer class.
     *
     * @param keySerializer the serializer class
     */
    public void setKeySerializer(Class<ObjectSerializer> keySerializer) {
        this.keySerializer = keySerializer;
    }

    /**
     * @return The expiry to use after the value is written
     */
    public Optional<Duration> getExpireAfterWrite() {
        return Optional.ofNullable(expireAfterWrite);
    }

    /**
     * @param expireAfterWrite The cache expiration duration after writing into it.
     */
    public void setExpireAfterWrite(Duration expireAfterWrite) {
        this.expireAfterWrite = expireAfterWrite;
    }

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed duration
     * has elapsed after the entry's creation, the most recent replacement of its value, or its last
     * read.
     *
     * @return The {@link Duration}
     */
    public Optional<Duration> getExpireAfterAccess() {
        return Optional.ofNullable(expireAfterAccess);
    }

    /**
     * @param expireAfterAccess The cache expiration duration after accessing it
     */
    public void setExpireAfterAccess(Duration expireAfterAccess) {
        this.expireAfterAccess = expireAfterAccess;
    }

    /**
     * @return The class path for an implementation of ExpirationAfterWritePolicy
     */
    public Optional<String> getExpirationAfterWritePolicy() {
        return Optional.ofNullable(expirationAfterWritePolicy);
    }

    /**
     * @param expirationAfterWritePolicy The class path for an implementation of ExpirationAfterWritePolicy
     */
    public void setExpirationAfterWritePolicy(String expirationAfterWritePolicy) {
        this.expirationAfterWritePolicy = expirationAfterWritePolicy;
    }

    /**
     * @return The charset used to serialize and deserialize values
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * @param charset The charset used to serialize and deserialize values
     */
    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    /**
     * Returns the count used for the scan command in invalidateAll().
     * Defaults to 100L.
     *
     * @return the count setting used in the redis scan
     */
    public Optional<Long> getInvalidateScanCount() {
        return Optional.ofNullable(invalidateScanCount);
    }

    /**
     * Sets the count used for the scan command in invalidateAll().
     *
     * @param invalidateScanCount the count setting to use with the redis scan
     */
    public void setInvalidateScanCount(Long invalidateScanCount) {
        this.invalidateScanCount = invalidateScanCount;
    }
}
