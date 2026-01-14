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

package io.seqera.data.range.impl;

import java.util.ArrayList;
import java.util.List;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.Tuple;

/**
 * Redis based implementation for range set
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(property = "redis.uri")
@Singleton
public class RedisRangeProvider implements RangeProvider {

    @Inject
    private JedisPool pool;

    @Override
    public void add(String key, String element, double score) {
        try (Jedis conn = pool.getResource()) {
            conn.zadd(key, score, element);
        }
    }

    private static final String SCRIPT = """
        local elements = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[2], 'LIMIT', ARGV[3], ARGV[4])
        if #elements > 0 then
            redis.call('ZREM', KEYS[1], unpack(elements))
        end
        return elements
        """;

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getRange(String key, double min, double max, int count, boolean remove) {
        try (Jedis conn = pool.getResource()) {
            List<String> result = new ArrayList<>();
            if (remove) {
                Object entries = conn.eval(SCRIPT, 1, key, String.valueOf(min), String.valueOf(max), "0", String.valueOf(count));
                if (entries instanceof List) {
                    result.addAll((List<String>) entries);
                }
            } else {
                List<Tuple> found = conn.zrangeByScoreWithScores(key, min, max, 0, count);
                for (Tuple tuple : found) {
                    result.add(tuple.getElement());
                }
            }
            return result;
        }
    }
}
