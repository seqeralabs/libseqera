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

package io.seqera.util.time;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility functions for handling duration
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public final class DurationUtils {

    private DurationUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static Duration randomDuration(Duration min, Duration max) {
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("Min duration must be less than or equal to max duration");
        }

        long minNanos = min.toNanos();
        long maxNanos = max.toNanos();
        long randomNanos = ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1);

        return Duration.ofNanos(randomNanos);
    }

    public static Duration randomDuration(Duration reference, float intervalPercentage) {
        if (intervalPercentage < 0 || intervalPercentage > 1) {
            throw new IllegalArgumentException("Interval percentage must be between 0 and 1");
        }

        long refNanos = reference.toNanos();
        long intervalNanos = (long) (refNanos * intervalPercentage);

        long minNanos = Math.max(0, refNanos - intervalNanos);
        long maxNanos = refNanos + intervalNanos;

        long randomNanos = ThreadLocalRandom.current().nextLong(minNanos, maxNanos + 1);

        return Duration.ofNanos(randomNanos);
    }
}
