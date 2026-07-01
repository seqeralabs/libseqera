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
package io.seqera.data.command;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskScheduler;
import io.seqera.activator.redis.RedisActivator;
import io.seqera.lock.LockConfig;
import io.seqera.lock.LockManager;
import io.seqera.lock.local.LocalLockManager;
import io.seqera.lock.redis.RedisLockManager;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import redis.clients.jedis.JedisPool;

/**
 * Self-provisions the {@link LockManager} used by {@link CommandServiceImpl} to guarantee that a
 * given command's handler is not executed concurrently on more than one replica.
 *
 * <p>The manager is provided under the reserved qualifier {@link #COMMAND_LOCK} so consumers do not
 * need to configure {@code seqera.lock.*} for the command queue to work, and so it does not clash
 * with any {@link LockManager} beans the host application defines via {@code seqera.lock.<name>}.
 * Because the qualifier doubles as a {@link LockConfig} name, {@link #COMMAND_LOCK} is deliberately
 * an unlikely configuration key and is reserved for this use.
 *
 * <p><b>Redis-mode runtime precondition:</b> the host must provide a {@link RedisActivator} bean and
 * a {@link JedisPool} bean (e.g. via {@code lib-jedis-pool}). This already holds wherever this module
 * runs against Redis, since the message stream and state store it depends on require the same pool.
 * When Redis is not active a purely in-JVM {@link LocalLockManager} is used instead.
 *
 * @author Paolo Di Tommaso
 */
@Factory
public class CommandLockFactory {

    /**
     * Reserved qualifier / {@link LockConfig} name for the command-queue single-runner lock.
     */
    public static final String COMMAND_LOCK = "cmd-queue-internal-lock";

    @Singleton
    @Named(COMMAND_LOCK)
    @Requires(bean = RedisActivator.class)
    LockManager redisCommandLock(JedisPool pool, TaskScheduler scheduler, CommandConfig config) {
        final var cfg = new LockConfig(COMMAND_LOCK);
        // watchdog stays enabled (LockConfig default) so the lock is held while the holder is alive
        // and expires by TTL on crash — do not disable it
        cfg.setAutoExpireDuration(config.commandLockDuration());
        return new RedisLockManager(cfg, pool, scheduler);
    }

    @Singleton
    @Named(COMMAND_LOCK)
    @Requires(missingBeans = RedisActivator.class)
    LockManager localCommandLock(CommandConfig config) {
        final var cfg = new LockConfig(COMMAND_LOCK);
        cfg.setAutoExpireDuration(config.commandLockDuration());
        return new LocalLockManager(cfg);
    }
}
