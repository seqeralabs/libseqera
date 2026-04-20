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

import java.time.Duration;

import io.seqera.data.stream.impl.RedisStreamConfig;
import jakarta.inject.Singleton;

/**
 * Test-only {@link RedisStreamConfig} bean — needed because
 * {@code DefaultRedisMessageStream} / {@code DefaultLocalMessageStream} are
 * now {@code @EachBean(RedisStreamConfig.class)}, so at least one config
 * bean must be present in the context for any {@code MessageStream} to
 * exist.
 */
@Singleton
public class TestRedisStreamConfig implements RedisStreamConfig {

    @Override
    public String getDefaultConsumerGroupName() {
        return "test-command-workers";
    }

    @Override
    public Duration getClaimTimeout() {
        return Duration.ofSeconds(1);
    }

    @Override
    public Duration getConsumerWarnTimeout() {
        return Duration.ofSeconds(5);
    }
}
