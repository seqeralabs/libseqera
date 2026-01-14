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

package io.seqera.data.range;

import java.util.List;

import io.seqera.data.range.impl.RangeProvider;

/**
 * Abstract implementation for range set similar to Redis {@code zrange}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public abstract class AbstractRangeStore implements RangeStore {

    private final RangeProvider delegate;

    protected abstract String getKey();

    protected AbstractRangeStore(RangeProvider provider) {
        this.delegate = provider;
    }

    @Override
    public void add(String name, double score) {
        delegate.add(getKey(), name, score);
    }

    @Override
    public List<String> getRange(double min, double max, int count) {
        return getRange(min, max, count, true);
    }

    public List<String> getRange(double min, double max, int count, boolean remove) {
        List<String> result = delegate.getRange(getKey(), min, max, count, remove);
        return result != null ? result : List.of();
    }
}
