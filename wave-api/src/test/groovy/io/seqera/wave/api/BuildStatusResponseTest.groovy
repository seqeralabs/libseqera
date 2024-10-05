/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
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

package io.seqera.wave.api

import java.time.Duration
import java.time.Instant

import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class BuildStatusResponseTest extends Specification {

    def 'should validate equals and hashcode' () {
        given:
        def n = Instant.now()
        def d = Duration.ofMinutes(1)
        def c1 = new BuildStatusResponse('123', BuildStatusResponse.Status.PENDING, n, d, true)
        def c2 = new BuildStatusResponse('123', BuildStatusResponse.Status.PENDING, n, d, true)
        def c3 = new BuildStatusResponse('321', BuildStatusResponse.Status.PENDING, n, d, true)

        expect:
        c1 == c2
        c1 != c3
        and:
        c1.hashCode() == c2.hashCode()
        c1.hashCode() != c3.hashCode()
    }

    def 'should validate creation' () {
        given:
        def n = Instant.now()
        def d = Duration.ofMinutes(1)
        def c1 = new BuildStatusResponse('123', BuildStatusResponse.Status.PENDING, n, d, true)

        expect:
        c1.id == '123'
        c1.status == BuildStatusResponse.Status.PENDING
        c1.startTime == n
        c1.duration == d
        c1.succeeded == true
    }

    def 'should create response object' () {
        given:
        def ts = Instant.now()
        def resp = new BuildStatusResponse(
                'test',
                BuildStatusResponse.Status.PENDING,
                ts,
                Duration.ofMinutes(1),
                true,
        )

        expect:
        resp.id == "test"
        resp.status == BuildStatusResponse.Status.PENDING
        resp.startTime == ts
        resp.duration == Duration.ofMinutes(1)
        resp.succeeded
    }
}
