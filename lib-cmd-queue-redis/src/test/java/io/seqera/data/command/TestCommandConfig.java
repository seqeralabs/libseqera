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
 */
package io.seqera.data.command;

import java.time.Duration;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Test implementation of CommandConfig for lib-data-command-queue tests.
 *
 * @author Paolo Di Tommaso
 */
@Singleton
public class TestCommandConfig implements CommandConfig {

    @Value("${command.poll-interval:100ms}")
    private Duration pollInterval;

    @Value("${command.execute-timeout:1s}")
    private Duration executeTimeout;

    @Value("${command.state.ttl:1h}")
    private Duration stateTtl;

    @Override
    public Duration pollInterval() {
        return pollInterval;
    }

    @Override
    public Duration executeTimeout() {
        return executeTimeout;
    }

    @Override
    public Duration stateTtl() {
        return stateTtl;
    }
}
