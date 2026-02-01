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

package io.seqera.lock.local;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.lock.LockConfig;
import io.seqera.lock.LockManager;

/**
 * Factory for creating named {@link LocalLockManager} instances.
 *
 * Activated when Redis is not available.
 *
 * @author Paolo Di Tommaso
 */
@Factory
@Requires(missingBeans = RedisActivator.class)
public class LocalLockManagerFactory {

    @EachBean(LockConfig.class)
    public LockManager localLockManager(LockConfig config) {
        return new LocalLockManager(config);
    }
}