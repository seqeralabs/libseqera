/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2025, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
