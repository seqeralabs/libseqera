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

import io.micronaut.context.annotation.EachBean;
import io.seqera.data.count.impl.CountProvider;

/**
 * Default {@link CountStore} implementation created for each {@link CountStoreConfig}.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@EachBean(CountStoreConfig.class)
public class DefaultCountStore extends AbstractCountStore {

    private final CountStoreConfig config;

    public DefaultCountStore(CountStoreConfig config, CountProvider provider) {
        super(provider);
        this.config = config;
    }

    @Override
    protected String getPrefix() {
        return config.getPrefix();
    }
}
