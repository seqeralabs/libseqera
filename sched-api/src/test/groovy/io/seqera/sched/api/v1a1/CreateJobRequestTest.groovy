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


import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CreateJobRequestTest extends Specification {

    def "should set command using withCommand method"() {
        given:
        def request = new CreateJobRequest()

        when:
        request.withCommand(["echo", "Hello World"])
        request.withImage("my-container")

        then:
        request.command == ["echo", "Hello World"]
        request.image == 'my-container'
    }

    def "should return correct string representation"() {
        given:
        def request = new CreateJobRequest()
                .withCommand(["run", "test"])
                .withImage("test-container")
                .withEnvironment([FOO: 'one'])

        expect:
        request.toString() == "CreateJobRequest{command=[run, test], image='test-container', environment='{FOO=one}'}"
    }

    def "should test equality of two CreateJobRequest objects"() {
        given:
        def request1 = new CreateJobRequest()
                .withCommand(["run", "test"])
                .withImage("test-container")
        def request2 = new CreateJobRequest()
                .withCommand(["run", "test"])
                .withImage("test-container")

        expect:
        request1 == request2
    }

    def "should test hashCode consistency"() {
        given:
        def request1 = new CreateJobRequest()
                .withCommand(["run", "test"])
                .withImage("test-container")
        def request2 = new CreateJobRequest()
                .withCommand(["run", "test"])
                .withImage("test-container")

        expect:
        request1.hashCode() == request2.hashCode()
    }
}
