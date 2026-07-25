/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.seqera.data.command;

import java.time.Duration;

import io.micronaut.context.annotation.Requires;
import io.seqera.data.workqueue.redis.RedisWorkQueueConfig;
import jakarta.inject.Singleton;

@Singleton
@Requires(env = "test")
public class TestRedisWorkQueueConfig implements RedisWorkQueueConfig {
    @Override
    public String getDefaultConsumerGroupName() {
        return "command-queue-test";
    }

    @Override
    public Duration getVisibilityTimeout() {
        return Duration.ofSeconds(1);
    }

    @Override
    public Duration getConsumerWarnTimeout() {
        return Duration.ofSeconds(5);
    }
}
