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
package io.seqera.jedis;

import java.util.Locale;

/**
 * How connections reach the Redis server, set with {@code redis.mode}.
 *
 * @author Paolo Di Tommaso
 */
public enum RedisMode {

    /**
     * Connect to the host in {@code redis.uri}. The default, and the only behavior before 1.3.0.
     */
    STANDALONE,

    /**
     * Connect to the primary of a single-shard cluster-mode server (e.g. AWS MemoryDB), found
     * through the discovery endpoint in {@code redis.uri}, and follow it across failovers.
     */
    CLUSTER_PRIMARY;

    /**
     * Parse a {@code redis.mode} value, e.g. {@code standalone} or {@code cluster-primary}.
     *
     * @param value the configured value, {@code null} or blank for the default
     * @return the matching mode
     * @throws IllegalArgumentException when the value names no mode
     */
    public static RedisMode parse(String value) {
        if (value == null || value.isBlank()) {
            return STANDALONE;
        }
        final String name = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(name);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid redis.mode '" + value + "' - expected 'standalone' or 'cluster-primary'");
        }
    }
}
