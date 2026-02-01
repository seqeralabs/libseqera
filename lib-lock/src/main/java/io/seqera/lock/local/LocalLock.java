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

import io.seqera.lock.Lock;

/**
 * In-memory implementation of {@link Lock} for local development and testing.
 *
 * @author Paolo Di Tommaso
 */
public class LocalLock implements Lock {

    private final LocalLockManager manager;
    private final String lockKey;
    private final String instanceId;

    LocalLock(LocalLockManager manager, String lockKey, String instanceId) {
        this.manager = manager;
        this.lockKey = lockKey;
        this.instanceId = instanceId;
    }

    @Override
    public boolean release() {
        return manager.release(lockKey, instanceId);
    }
}
