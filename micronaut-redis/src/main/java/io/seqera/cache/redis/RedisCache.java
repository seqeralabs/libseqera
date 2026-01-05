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

import io.micronaut.cache.AsyncCache;
import io.micronaut.cache.SyncCache;
import io.micronaut.cache.serialize.DefaultStringKeySerializer;
import io.micronaut.context.BeanLocator;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.serialize.JdkSerializer;
import io.micronaut.core.serialize.ObjectSerializer;
import io.micronaut.core.type.Argument;
import io.seqera.cache.redis.expiration.ConstantExpirationAfterWritePolicy;
import io.seqera.cache.redis.expiration.ExpirationAfterWritePolicy;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.NonNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * An implementation of {@link SyncCache} for Jedis / Redis.
 * This implementation uses JedisPool for connection management.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EachBean(RedisCacheConfiguration.class)
@Requires(classes = SyncCache.class)
public class RedisCache implements SyncCache<JedisPool>, AutoCloseable {

    private final JedisPool jedisPool;
    private final ObjectSerializer keySerializer;
    private final ObjectSerializer valueSerializer;
    private final RedisCacheConfiguration redisCacheConfiguration;
    private final ExpirationAfterWritePolicy expireAfterWritePolicy;
    private final Long expireAfterAccess;
    private final Long invalidateScanCount;
    private final ExecutorService asyncExecutor;
    private final RedisAsyncCache asyncCache;

    /**
     * Creates a new Redis cache for the given arguments.
     *
     * @param jedisPool                     The Jedis connection pool
     * @param defaultRedisCacheConfiguration The default configuration
     * @param redisCacheConfiguration        The cache-specific configuration
     * @param conversionService              The conversion service
     * @param beanLocator                    The bean locator
     */
    public RedisCache(
            JedisPool jedisPool,
            DefaultRedisCacheConfiguration defaultRedisCacheConfiguration,
            RedisCacheConfiguration redisCacheConfiguration,
            ConversionService conversionService,
            BeanLocator beanLocator
    ) {
        if (redisCacheConfiguration == null) {
            throw new IllegalArgumentException("Redis cache configuration cannot be null");
        }

        this.jedisPool = jedisPool;
        this.redisCacheConfiguration = redisCacheConfiguration;
        this.expireAfterWritePolicy = configureExpirationAfterWritePolicy(redisCacheConfiguration, beanLocator);

        this.keySerializer = redisCacheConfiguration
                .getKeySerializer()
                .flatMap(beanLocator::findOrInstantiateBean)
                .orElse(
                        defaultRedisCacheConfiguration
                                .getKeySerializer()
                                .flatMap(beanLocator::findOrInstantiateBean)
                                .orElse(newDefaultKeySerializer(redisCacheConfiguration, conversionService))
                );

        this.valueSerializer = redisCacheConfiguration
                .getValueSerializer()
                .flatMap(beanLocator::findOrInstantiateBean)
                .orElse(
                        defaultRedisCacheConfiguration
                                .getValueSerializer()
                                .flatMap(beanLocator::findOrInstantiateBean)
                                .orElse(new JdkSerializer(conversionService))
                );

        this.expireAfterAccess = redisCacheConfiguration
                .getExpireAfterAccess()
                .map(Duration::toMillis)
                .orElse(defaultRedisCacheConfiguration.getExpireAfterAccess().map(Duration::toMillis).orElse(null));

        this.invalidateScanCount = redisCacheConfiguration.getInvalidateScanCount().orElse(100L);

        this.asyncExecutor = Executors.newCachedThreadPool();
        this.asyncCache = new RedisAsyncCache();
    }

    @Override
    public String getName() {
        return redisCacheConfiguration.getCacheName();
    }

    @Override
    public JedisPool getNativeCache() {
        return jedisPool;
    }

    @NonNull
    @Override
    public <T> Optional<T> get(@NonNull Object key, @NonNull Argument<T> requiredType) {
        byte[] serializedKey = serializeKey(key);
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] data = jedis.get(serializedKey);
            if (expireAfterAccess != null && data != null) {
                jedis.pexpire(serializedKey, expireAfterAccess);
            }
            if (data != null) {
                return valueSerializer.deserialize(data, requiredType);
            }
            return Optional.empty();
        }
    }

    @NonNull
    @Override
    public <T> T get(@NonNull Object key, @NonNull Argument<T> requiredType, @NonNull Supplier<T> supplier) {
        byte[] serializedKey = serializeKey(key);
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] data = jedis.get(serializedKey);
            if (data != null) {
                Optional<T> deserialized = valueSerializer.deserialize(data, requiredType);
                if (deserialized.isPresent()) {
                    if (expireAfterAccess != null) {
                        jedis.pexpire(serializedKey, expireAfterAccess);
                    }
                    return deserialized.get();
                }
            }
        }

        T value = supplier.get();
        putValue(serializedKey, value);
        return value;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> putIfAbsent(@NonNull Object key, @NonNull T value) {
        byte[] serializedKey = serializeKey(key);
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] existingData = jedis.get(serializedKey);
            if (existingData != null) {
                return valueSerializer.deserialize(existingData, Argument.of((Class<T>) value.getClass()));
            }
            putValue(serializedKey, value);
            return Optional.empty();
        }
    }

    @Override
    public void put(@NonNull Object key, @NonNull Object value) {
        byte[] serializedKey = serializeKey(key);
        putValue(serializedKey, value);
    }

    @Override
    public void invalidate(@NonNull Object key) {
        byte[] serializedKey = serializeKey(key);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(serializedKey);
        }
    }

    @Override
    public void invalidateAll() {
        String pattern = getKeysPattern();
        try (Jedis jedis = jedisPool.getResource()) {
            ScanParams params = new ScanParams()
                    .match(pattern)
                    .count(invalidateScanCount.intValue());
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<byte[]> scanResult = jedis.scan(cursor.getBytes(redisCacheConfiguration.getCharset()), params);
                List<byte[]> keys = scanResult.getResult();
                if (!keys.isEmpty()) {
                    jedis.del(keys.toArray(new byte[0][]));
                }
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
    }

    @NonNull
    @Override
    public AsyncCache<JedisPool> async() {
        return asyncCache;
    }

    @PreDestroy
    @Override
    public void close() {
        asyncExecutor.shutdown();
        // Don't close the pool - it's shared and managed externally
    }

    /**
     * Serialize a key using the configured key serializer.
     *
     * @param key The key to serialize
     * @return The serialized key bytes
     */
    protected byte[] serializeKey(Object key) {
        return keySerializer.serialize(key)
                .orElseThrow(() -> new IllegalArgumentException("Key cannot be null"));
    }

    /**
     * Get the Redis key pattern for this cache.
     *
     * @return The key pattern
     */
    protected String getKeysPattern() {
        return getName() + ":*";
    }

    /**
     * Put a value into the cache with the configured expiration.
     *
     * @param serializedKey The serialized key
     * @param value         The value to store
     * @param <T>           The value type
     */
    protected <T> void putValue(byte[] serializedKey, T value) {
        Optional<byte[]> serialized = valueSerializer.serialize(value);
        try (Jedis jedis = jedisPool.getResource()) {
            if (serialized.isPresent()) {
                byte[] bytes = serialized.get();
                if (expireAfterWritePolicy != null) {
                    long ttl = expireAfterWritePolicy.getExpirationAfterWrite(value);
                    jedis.psetex(serializedKey, ttl, bytes);
                } else {
                    jedis.set(serializedKey, bytes);
                }
            } else {
                jedis.del(serializedKey);
            }
        }
    }

    private ExpirationAfterWritePolicy configureExpirationAfterWritePolicy(
            AbstractRedisCacheConfiguration redisCacheConfiguration,
            BeanLocator beanLocator
    ) {
        Optional<Duration> expireAfterWrite = redisCacheConfiguration.getExpireAfterWrite();
        Optional<String> expirationAfterWritePolicy = redisCacheConfiguration.getExpirationAfterWritePolicy();

        if (expireAfterWrite.isPresent()) {
            Duration expiration = expireAfterWrite.get();
            return new ConstantExpirationAfterWritePolicy(expiration.toMillis());
        } else if (expirationAfterWritePolicy.isPresent()) {
            return findExpirationAfterWritePolicyBean(beanLocator, expirationAfterWritePolicy.get());
        }
        return null;
    }

    private ExpirationAfterWritePolicy findExpirationAfterWritePolicyBean(BeanLocator beanLocator, String className) {
        try {
            Optional<?> bean = beanLocator.findOrInstantiateBean(Class.forName(className));
            if (bean.isPresent()) {
                Object foundBean = bean.get();
                if (foundBean instanceof ExpirationAfterWritePolicy) {
                    return (ExpirationAfterWritePolicy) foundBean;
                }
                throw new ConfigurationException("Redis expiration-after-write-policy was not of type ExpirationAfterWritePolicy");
            } else {
                throw new ConfigurationException("Redis expiration-after-write-policy was not found");
            }
        } catch (ClassNotFoundException e) {
            throw new ConfigurationException("Redis expiration-after-write-policy class not found: " + className);
        }
    }

    private DefaultStringKeySerializer newDefaultKeySerializer(
            RedisCacheConfiguration redisCacheConfiguration,
            ConversionService conversionService
    ) {
        return new DefaultStringKeySerializer(
                redisCacheConfiguration.getCacheName(),
                redisCacheConfiguration.getCharset(),
                conversionService
        );
    }

    /**
     * Async cache implementation that wraps sync operations with CompletableFuture.
     */
    protected class RedisAsyncCache implements AsyncCache<JedisPool> {

        @NonNull
        @Override
        public <T> CompletableFuture<Optional<T>> get(@NonNull Object key, @NonNull Argument<T> requiredType) {
            return CompletableFuture.supplyAsync(
                    () -> RedisCache.this.get(key, requiredType),
                    asyncExecutor
            );
        }

        @NonNull
        @Override
        public <T> CompletableFuture<T> get(@NonNull Object key, @NonNull Argument<T> requiredType, @NonNull Supplier<T> supplier) {
            return CompletableFuture.supplyAsync(
                    () -> RedisCache.this.get(key, requiredType, supplier),
                    asyncExecutor
            );
        }

        @NonNull
        @Override
        public <T> CompletableFuture<Optional<T>> putIfAbsent(@NonNull Object key, @NonNull T value) {
            return CompletableFuture.supplyAsync(
                    () -> RedisCache.this.putIfAbsent(key, value),
                    asyncExecutor
            );
        }

        @NonNull
        @Override
        public CompletableFuture<Boolean> put(@NonNull Object key, @NonNull Object value) {
            return CompletableFuture.supplyAsync(() -> {
                RedisCache.this.put(key, value);
                return true;
            }, asyncExecutor);
        }

        @NonNull
        @Override
        public CompletableFuture<Boolean> invalidate(@NonNull Object key) {
            return CompletableFuture.supplyAsync(() -> {
                RedisCache.this.invalidate(key);
                return true;
            }, asyncExecutor);
        }

        @NonNull
        @Override
        public CompletableFuture<Boolean> invalidateAll() {
            return CompletableFuture.supplyAsync(() -> {
                RedisCache.this.invalidateAll();
                return true;
            }, asyncExecutor);
        }

        @Override
        public String getName() {
            return RedisCache.this.getName();
        }

        @Override
        public JedisPool getNativeCache() {
            return RedisCache.this.getNativeCache();
        }
    }
}
