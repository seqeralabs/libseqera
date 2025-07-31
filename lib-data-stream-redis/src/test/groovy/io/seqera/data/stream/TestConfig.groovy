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

package io.seqera.data.stream

import java.time.Duration

import io.micronaut.context.annotation.Requires
import io.seqera.data.stream.impl.RedisStreamConfig
import jakarta.inject.Singleton
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Requires(env = 'test')
@Singleton
class TestConfig implements RedisStreamConfig {

    @Override
    String getDefaultConsumerGroupName() {
        return "wave-message-stream"
    }

    @Override
    Duration getClaimTimeout() {
        return Duration.ofSeconds(1)
    }

    @Override
    Duration getConsumerWarnTimeout() {
        return Duration.ofSeconds(5)
    }
}
