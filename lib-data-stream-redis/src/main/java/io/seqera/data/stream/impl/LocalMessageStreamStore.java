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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import io.micronaut.context.annotation.Requires;
import io.seqera.activator.redis.RedisActivator;
import jakarta.inject.Singleton;

/**
 * Shared in-memory backing store for every
 * {@link DefaultLocalMessageStream} bean within one Micronaut context.
 *
 * <p>A single {@code @Singleton} bean holds the stream map so that all
 * {@code @EachBean(RedisStreamConfig)} instances of
 * {@code DefaultLocalMessageStream} address the same set of streams — this
 * is what makes an atomic cross-stream hand-off work in-memory (the offer
 * queued on one instance lands in the same map polled by the consumer
 * registered on another instance).</p>
 *
 * <p>Scoped to a Micronaut context — every {@code @MicronautTest} creates
 * its own store, so tests stay isolated.</p>
 */
@Requires(missingBeans = RedisActivator.class)
@Singleton
public class LocalMessageStreamStore {

    final ConcurrentHashMap<String, LinkedBlockingQueue<String>> delegate = new ConcurrentHashMap<>();
}
