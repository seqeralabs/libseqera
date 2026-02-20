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

package io.seqera.data.count;

import io.seqera.data.count.impl.CountProvider;

/**
 * Abstract implementation for distributed counter similar to Redis {@code INCRBY}/{@code DECRBY}
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public abstract class AbstractCountStore implements CountStore {

    private final CountProvider delegate;

    protected abstract String getPrefix();

    protected AbstractCountStore(CountProvider provider) {
        this.delegate = provider;
    }

    protected String key0(String k) {
        return getPrefix() + ':' + k;
    }

    @Override
    public long increment(String key) {
        return increment(key, 1);
    }

    @Override
    public long increment(String key, long value) {
        return delegate.increment(key0(key), value);
    }

    @Override
    public long decrement(String key) {
        return decrement(key, 1);
    }

    @Override
    public long decrement(String key, long value) {
        return delegate.decrement(key0(key), value);
    }

    @Override
    public long get(String key) {
        return delegate.get(key0(key));
    }

    @Override
    public void clear(String key) {
        delegate.clear(key0(key));
    }
}
