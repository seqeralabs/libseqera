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
import redis.clients.jedis.JedisPool;

/**
 * Micronaut-managed default wiring for {@link RedisMessageStream}. Annotated
 * with {@code @EachBean(RedisStreamConfig.class)}, so one bean is produced
 * for every {@link RedisStreamConfig} bean present in the context (each with
 * the same qualifier as its config). Applications that declare multiple
 * named {@code RedisStreamConfig} beans automatically get one
 * {@code RedisMessageStream} per config — no extra factory needed.
 *
 * <p>For programmatic construction outside the Micronaut bean graph, use the
 * {@link RedisMessageStream} base class directly.</p>
 *
 * @author Paolo Di Tommaso
 */
@Requires(bean = RedisActivator.class)
@EachBean(RedisStreamConfig.class)
public class DefaultRedisMessageStream extends RedisMessageStream {

    public DefaultRedisMessageStream(JedisPool pool, @Parameter RedisStreamConfig config) {
        super(pool, config);
    }
}
