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

package io.seqera.activator.redis;

/**
 * Marker interface for Redis activation in application components.
 * 
 * <p>This interface serves as a conditional activation marker for Redis-based
 * functionality. Components implementing this interface indicate they require
 * Redis connectivity and should only be activated when Redis infrastructure
 * is available.
 * 
 * <p>Typical usage involves implementing this interface in beans that should
 * be conditionally created based on Redis availability:
 * <pre>
 * &#64;Singleton
 * &#64;Requires(beans = RedisActivator.class)
 * class RedisStreamProcessor implements StreamProcessor {
 *     // Redis-dependent implementation
 * }
 * </pre>
 * 
 * <p>The actual Redis activation logic is typically provided by concrete
 * implementations that define specific activation conditions such as:
 * <ul>
 *   <li>Environment-based activation (e.g., 'redis' environment active)</li>
 *   <li>Configuration-based activation (e.g., 'redis.uri' property set)</li>
 *   <li>Programmatic activation based on runtime conditions</li>
 * </ul>
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 */
public interface RedisActivator {
}
