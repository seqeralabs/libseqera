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

package io.seqera.data.range.impl;

import java.util.List;

/**
 * Contract for range store provider
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public interface RangeProvider {

    void add(String key, String member, double score);

    /**
     * Add {@code member} with {@code score} to the sorted set at {@code key}.
     * If {@code member} already exists, only update its score when the new
     * score is strictly less than the current one. Always adds new members.
     *
     * @return {@code true} if added or updated, {@code false} if an earlier
     *         (or equal) score was kept
     */
    boolean addIfLess(String key, String member, double score);

    List<String> getRange(String key, double min, double max, int count, boolean remove);
}
