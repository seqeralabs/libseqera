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

package io.seqera.cache.tiered;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import io.seqera.serde.Encodable;
import io.seqera.serde.encode.StringEncodingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract implementation of a tiered-cache mechanism using Caffeine as L1 (local)
 * cache and Redis as L2 (distributed) cache.
 *
 * <p>This implementation provides a two-tier caching strategy:</p>
 * <ul>
 *   <li><b>L1 Cache (Caffeine):</b> Fast, in-memory local cache with configurable size</li>
 *   <li><b>L2 Cache (Redis):</b> Distributed cache shared across multiple instances</li>
 * </ul>
 *
 * <p>The caching flow is as follows:</p>
 * <ol>
 *   <li>Check L1 cache first (fastest)</li>
 *   <li>On L1 miss, check L2 cache and hydrate L1 if found</li>
 *   <li>On L2 miss, optionally invoke a loader function to compute the value</li>
 *   <li>Store computed values in both L1 and L2</li>
 * </ol>
 *
 * <p>This allows deployment in distributed environments while maintaining fast local
 * access. Note that strong consistency is not guaranteed across instances.</p>
 *
 * <p>Thread Safety:</p>
 * <p>This class uses per-key locking to ensure thread-safe access to cache entries.
 * Multiple threads can safely access different keys concurrently, while access to
 * the same key is serialized to prevent race conditions.</p>
 *
 * @param <K> the type of keys maintained by this cache; must be either a subtype of
 *           {@link CharSequence} or an implementation of {@link TieredKey}
 * @param <V> the type of values maintained by this cache; must extend {@link Encodable}
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public abstract class AbstractTieredCache<K, V extends Encodable> implements TieredCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(AbstractTieredCache.class);

    /**
     * Internal entry wrapper that stores the value along with its expiration timestamp.
     */
    public static class Entry implements Encodable {
        private Encodable value;
        private long expiresAt;

        public Entry() {
        }

        public Entry(Encodable value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        public Encodable getValue() {
            return value;
        }

        public long getExpiresAt() {
            return expiresAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Entry entry = (Entry) o;
            return expiresAt == entry.expiresAt && Objects.equals(value, entry.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, expiresAt);
        }

        @Override
        public String toString() {
            return "Entry{value=" + value + ", expiresAt=" + expiresAt + '}';
        }
    }

    /**
     * A simple immutable pair record for returning two values together.
     * Used by {@link #getOrCompute(Object, Function)} to return both a value and its TTL.
     *
     * @param <F> the type of the first element
     * @param <S> the type of the second element
     */
    public record Pair<F, S>(F first, S second) {}

    private final StringEncodingStrategy<Entry> encoder;
    private volatile Cache<String, Entry> l1;
    private final L2TieredCache<String, String> l2;
    private final LoadingCache<String, Lock> locks;

    protected AbstractTieredCache(L2TieredCache<String, String> l2, StringEncodingStrategy<Entry> encoder) {
        if (l2 == null) {
            log.warn("Missing L2 cache for tiered cache '{}'", getName());
        }
        this.l2 = l2;
        this.encoder = encoder;
        this.locks = Caffeine.newBuilder()
                .maximumSize(5_000)
                .weakKeys()
                .build(this::createLock);
    }

    private Lock createLock(String key) {
        return new ReentrantLock();
    }

    private Cache<String, Entry> getL1() {
        if (l1 != null) {
            return l1;
        }

        final Lock sync = locks.get("sync-l1");
        sync.lock();
        try {
            if (l1 != null) {
                return l1;
            }

            log.info("Cache '{}' config - prefix={}; max-size: {}", getName(), getPrefix(), getMaxSize());
            l1 = Caffeine.newBuilder()
                    .maximumSize(getMaxSize())
                    .removalListener(createRemovalListener())
                    .build();
            return l1;
        } finally {
            sync.unlock();
        }
    }

    /**
     * Returns the maximum size of the L1 (local) cache.
     *
     * @return the maximum number of entries in the L1 cache
     */
    protected abstract int getMaxSize();

    /**
     * Returns the name of this cache for logging and identification purposes.
     *
     * @return the cache name
     */
    protected abstract String getName();

    /**
     * Returns the key prefix used for L2 cache entries.
     *
     * <p>This prefix is prepended to all keys when storing in the L2 cache
     * to provide namespace isolation.</p>
     *
     * @return the key prefix
     */
    protected abstract String getPrefix();

    private RemovalListener<String, Entry> createRemovalListener() {
        return (key, value, cause) -> {
            if (log.isTraceEnabled()) {
                log.trace("Cache '{}' removing key={}; value={}; cause={}", getName(), key, value, cause);
            }
        };
    }

    protected String k0(K key) {
        if (key instanceof CharSequence) {
            return key.toString();
        }
        if (key instanceof TieredKey) {
            return ((TieredKey) key).stableHash();
        }
        if (key == null) {
            throw new IllegalArgumentException("Tiered cache key cannot be null");
        } else {
            throw new IllegalArgumentException("Tiered cache key type - offending value: " + key + "; type: " + key.getClass());
        }
    }

    @Override
    public V get(K key) {
        return getOrCompute0(k0(key), null);
    }

    /**
     * Retrieves the value associated with the specified key, using a loader function
     * if the value is not found in either cache tier.
     *
     * @param key the key whose associated value is to be returned
     * @param loader a function to compute the value if not found; may be null
     * @param ttl the time-to-live for newly computed values
     * @return the cached or computed value, or null if not found and no loader provided
     */
    public V getOrCompute(K key, Function<String, V> loader, Duration ttl) {
        if (loader == null) {
            return getOrCompute0(k0(key), null);
        }
        return getOrCompute0(k0(key), k -> {
            V v = loader.apply(k);
            return v != null ? new Pair<>(v, ttl) : null;
        });
    }

    /**
     * Retrieves the value associated with the specified key, using a loader function
     * that returns both the value and its TTL.
     *
     * @param key the key whose associated value is to be returned
     * @param loader a function that returns a Pair of value and TTL
     * @return the cached or computed value
     */
    public V getOrCompute(K key, Function<String, Pair<V, Duration>> loader) {
        return getOrCompute0(k0(key), loader);
    }

    @SuppressWarnings("unchecked")
    private V getOrCompute0(String key, Function<String, Pair<V, Duration>> loader) {
        if (key == null) {
            throw new IllegalArgumentException("Argument key cannot be null");
        }

        if (log.isTraceEnabled()) {
            log.trace("Cache '{}' checking key={}", getName(), key);
        }

        // Try L1 cache first
        V value = l1Get(key);
        if (value != null) {
            if (log.isTraceEnabled()) {
                log.trace("Cache '{}' L1 hit (a) - key={} => value={}", getName(), key, value);
            }
            return value;
        }

        final Lock sync = locks.get(key);
        sync.lock();
        try {
            value = l1Get(key);
            if (value != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Cache '{}' L1 hit (b) - key={} => value={}", getName(), key, value);
                }
                return value;
            }

            // Fallback to L2 cache
            final Entry entry = l2GetEntry(key);
            if (entry != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Cache '{}' L2 hit - key={} => entry={}", getName(), key, entry);
                }
                // Rehydrate L1 cache
                getL1().put(key, entry);
                return (V) entry.value;
            }

            // Still no value found, use loader function to fetch the value
            if (value == null && loader != null) {
                if (log.isTraceEnabled()) {
                    log.trace("Cache '{}' invoking loader - key={}", getName(), key);
                }
                final Pair<V, Duration> ret = loader.apply(key);
                value = ret != null ? ret.first() : null;
                Duration ttl = ret != null ? ret.second() : null;
                if (value != null && ttl != null) {
                    final long exp = Instant.now().plus(ttl).toEpochMilli();
                    final Entry newEntry = new Entry(value, exp);
                    l1Put(key, newEntry);
                    l2Put(key, newEntry, ttl);
                }
            }

            if (log.isTraceEnabled()) {
                log.trace("Cache '{}' missing value - key={} => value={}", getName(), key, value);
            }
            return value;
        } finally {
            sync.unlock();
        }
    }

    @Override
    public void put(K key, V value, Duration ttl) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key argument cannot be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("Cache value argument cannot be null");
        }

        if (log.isTraceEnabled()) {
            log.trace("Cache '{}' putting - key={}; value={}", getName(), key, value);
        }

        final long exp = System.currentTimeMillis() + ttl.toMillis();
        final Entry entry = new Entry(value, exp);
        l1Put(k0(key), entry);
        l2Put(k0(key), entry, ttl);
    }

    protected String key0(String k) {
        return getPrefix() + ':' + k;
    }

    @SuppressWarnings("unchecked")
    protected V l1Get(String key) {
        Entry entry = l1GetEntry(key);
        return entry != null ? (V) entry.value : null;
    }

    protected Entry l1GetEntry(String key) {
        final Entry entry = getL1().getIfPresent(key);
        if (entry == null) {
            return null;
        }

        if (System.currentTimeMillis() > entry.expiresAt) {
            if (log.isTraceEnabled()) {
                log.trace("Cache '{}' L1 expired - key={} => entry={}", getName(), key, entry);
            }
            return null;
        }
        return entry;
    }

    protected void l1Put(String key, Entry entry) {
        getL1().put(key, entry);
    }

    protected Entry l2GetEntry(String key) {
        if (l2 == null) {
            return null;
        }

        final String raw = l2.get(key0(key));
        if (raw == null) {
            return null;
        }

        final Entry entry = encoder.decode(raw);
        if (System.currentTimeMillis() > entry.expiresAt) {
            if (log.isTraceEnabled()) {
                log.trace("Cache '{}' L2 expired - key={} => value={}", getName(), key, entry);
            }
            return null;
        }
        return entry;
    }

    @SuppressWarnings("unchecked")
    protected V l2Get(String key) {
        Entry entry = l2GetEntry(key);
        return entry != null ? (V) entry.value : null;
    }

    protected void l2Put(String key, Entry entry, Duration ttl) {
        if (l2 != null) {
            final String raw = encoder.encode(entry);
            l2.put(key0(key), raw, ttl);
        }
    }

    /**
     * Invalidates all entries in the L1 cache.
     *
     * <p>Note: This only affects the local L1 cache. L2 cache entries remain
     * unchanged and may repopulate the L1 cache on subsequent access.</p>
     */
    public void invalidateAll() {
        getL1().invalidateAll();
    }

}
