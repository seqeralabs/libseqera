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
package io.seqera.data.stream.impl;

import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;

/**
 * Micronaut-managed default wiring for {@link LocalMessageStream}. Annotated
 * with {@code @EachBean(RedisStreamConfig.class)}, so one in-memory stream
 * instance is produced per {@link RedisStreamConfig} bean (carrying the same
 * qualifier). Active only when Redis is not available — the companion
 * {@link DefaultRedisMessageStream} takes over when {@code RedisActivator}
 * is present.
 *
 * <p>The {@code RedisStreamConfig} argument is unused at runtime (the local
 * implementation stores messages in-memory regardless of config values) but is
 * required by {@code @EachBean} to establish the qualifier cascade.</p>
 *
 * @author Paolo Di Tommaso
 */
@Requires(missingBeans = RedisActivator.class)
@EachBean(RedisStreamConfig.class)
public class DefaultLocalMessageStream extends LocalMessageStream {

    public DefaultLocalMessageStream(@Parameter RedisStreamConfig config) {
        // config ignored — used only for the @EachBean qualifier cascade
    }
}
