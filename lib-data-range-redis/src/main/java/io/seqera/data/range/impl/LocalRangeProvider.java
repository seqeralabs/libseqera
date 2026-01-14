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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local based implementation for a range set
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(missingProperty = "redis.uri")
@Singleton
public class LocalRangeProvider implements RangeProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalRangeProvider.class);

    private final Map<String, Map<String, Double>> store = new HashMap<>();

    @Override
    public void add(String key, String element, double score) {
        Map<String, Double> map = store.computeIfAbsent(key, k -> new HashMap<>());
        map.put(element, score);
        log.trace("* add range - store: {}", store);
    }

    @Override
    public List<String> getRange(String key, double min, double max, int count, boolean remove) {
        Map<String, Double> map = store.getOrDefault(key, new HashMap<>());
        List<String> result = new ArrayList<>();

        // Sort entries by value and iterate
        List<Map.Entry<String, Double>> sortedEntries = new ArrayList<>(map.entrySet());
        sortedEntries.sort(Map.Entry.comparingByValue());

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sortedEntries) {
            if (result.size() >= count) {
                break;
            }
            if (entry.getValue() >= min && entry.getValue() <= max) {
                result.add(entry.getKey());
                if (remove) {
                    toRemove.add(entry.getKey());
                }
            }
        }

        // Remove after iteration to avoid ConcurrentModificationException
        for (String k : toRemove) {
            map.remove(k);
        }

        log.trace("* get range result={} - store: {}", result, store);
        return result;
    }
}
