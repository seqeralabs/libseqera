/*
 * Copyright 2013-2025, Seqera Labs
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

package io.seqera.sched.api.v1a1

import io.seqera.sched.api.v1a1.CreateJobResponse

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
import spock.lang.Specification

class CreateJobResponseTest extends Specification {

    def "should set jobId using withJobId method"() {
        given:
        def response = new CreateJobResponse()
        def jobId = "12345"

        when:
        response.withJobId(jobId)

        then:
        response.jobId == jobId
    }

    def "should set error using withError method"() {
        given:
        def response = new CreateJobResponse()
        def errorMessage = "Some error occurred"

        when:
        response.withError(errorMessage)

        then:
        response.error == errorMessage
    }

    def "should return correct string representation"() {
        given:
        def response = new CreateJobResponse()
                .withJobId("12345")
                .withError("Some error occurred")

        expect:
        response.toString() == "CreateJobResponse{jobId='12345', error='Some error occurred'}"
    }

    def "should test equality of two CreateJobResponse objects"() {
        given:
        def response1 = new CreateJobResponse()
                .withJobId("12345")
                .withError("Some error occurred")
        def response2 = new CreateJobResponse()
                .withJobId("12345")
                .withError("Some error occurred")

        expect:
        response1 == response2
    }

    def "should test hashCode consistency"() {
        given:
        def response1 = new CreateJobResponse()
                .withJobId("12345")
                .withError("Some error occurred")
        def response2 = new CreateJobResponse()
                .withJobId("12345")
                .withError("Some error occurred")

        expect:
        response1.hashCode() == response2.hashCode()
    }
}
