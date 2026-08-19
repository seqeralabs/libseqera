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
package io.seqera.data.command.store

import java.time.Instant

import com.fasterxml.jackson.annotation.JsonTypeInfo
import groovy.transform.Canonical
import io.seqera.data.command.CommandState
import io.seqera.data.command.CommandStatus
import io.seqera.serde.jackson.JacksonEncodingStrategy
import spock.lang.Specification

/**
 * Tests to validate JacksonEncodingStrategy can serialize/deserialize
 * CommandState with arbitrary params and result types using @JsonTypeInfo.
 */
class CommandStateSerializationTest extends Specification {

    // Sample params classes
    @Canonical
    static class CreateJobParams {
        String image
        String command
        int cpu
        int memory
    }

    @Canonical
    static class DeleteJobParams {
        String jobId
        boolean force
    }

    // Sample result classes
    @Canonical
    static class CreateJobResult {
        String jobId
        String status
        Instant startedAt
    }

    @Canonical
    static class DeleteJobResult {
        boolean deleted
    }

    // CommandState with @JsonTypeInfo to encode type in params/result fields
    @Canonical
    static class TypedCommandState<P, R> {
        String id
        String type
        CommandStatus status
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
        P params
        @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
        R result
        String error
        Instant createdAt
        Instant startedAt
        Instant completedAt
    }

    def 'should serialize and deserialize with wildcard types'() {
        given:
        def encoder = new JacksonEncodingStrategy<TypedCommandState<?, ?>>() {}
        def now = Instant.now()
        def params = new CreateJobParams('alpine:latest', 'echo hello', 1, 512)
        def result = new CreateJobResult('job-123', 'RUNNING', now)
        def state = new TypedCommandState<>(
                'cmd-abc',
                'create-job',
                CommandStatus.SUCCEEDED,
                params,
                result,
                null,
                now,
                now,
                now
        )

        when:
        def json = encoder.encode(state)

        then:
        // JSON contains @class type info
        json.contains('@class')
        json.contains('CreateJobParams')
        json.contains('CreateJobResult')

        when:
        def decoded = encoder.decode(json)

        then:
        decoded.id == 'cmd-abc'
        decoded.status == CommandStatus.SUCCEEDED
        // With @JsonTypeInfo, params and result are deserialized to their original types
        decoded.params instanceof CreateJobParams
        decoded.params.image == 'alpine:latest'
        decoded.params.cpu == 1
        decoded.result instanceof CreateJobResult
        decoded.result.jobId == 'job-123'
    }

    def 'should serialize and deserialize different command types'() {
        given:
        def encoder = new JacksonEncodingStrategy<TypedCommandState<?, ?>>() {}
        def now = Instant.now()
        def params = new DeleteJobParams('job-456', true)
        def result = new DeleteJobResult(true)
        def state = new TypedCommandState<>(
                'cmd-del',
                'delete-job',
                CommandStatus.SUCCEEDED,
                params,
                result,
                null,
                now,
                null,
                now
        )

        when:
        def json = encoder.encode(state)
        def decoded = encoder.decode(json)

        then:
        // Different types are correctly restored
        decoded.params instanceof DeleteJobParams
        decoded.params.jobId == 'job-456'
        decoded.result instanceof DeleteJobResult
        decoded.result.deleted
    }

    def 'should handle null result for processing commands'() {
        given:
        def encoder = new JacksonEncodingStrategy<TypedCommandState<?, ?>>() {}
        def now = Instant.now()
        def params = new CreateJobParams('ubuntu:22.04', 'sleep 60', 2, 1024)
        def state = new TypedCommandState<>(
                'cmd-xyz',
                'create-job',
                CommandStatus.PROCESSING,
                params,
                null,  // No result yet
                null,
                now,
                now,
                null
        )

        when:
        def json = encoder.encode(state)
        def decoded = encoder.decode(json)

        then:
        decoded.params instanceof CreateJobParams
        decoded.params.image == 'ubuntu:22.04'
        decoded.result == null
        decoded.status == CommandStatus.PROCESSING
    }

    def 'should decode legacy JSON without error-tracking fields into the real record'() {
        given: 'the encoder as wired by CommandStateStoreFactory'
        def encoder = new JacksonEncodingStrategy<CommandState>() {}
        def now = Instant.now()
        and: 'old-format JSON, before errorsCount/modifiedAt existed'
        def paramsClass = CreateJobParams.name
        def legacyJson = """\
            {
                "id": "cmd-legacy",
                "type": "create-job",
                "status": "RUNNING",
                "params": {"@class": "${paramsClass}", "image": "alpine:latest", "command": "echo hi", "cpu": 1, "memory": 512},
                "result": null,
                "error": null,
                "createdAt": "${now}",
                "startedAt": "${now}",
                "completedAt": null
            }""".stripIndent()

        when:
        def decoded = encoder.decode(legacyJson)

        then: 'existing fields survive'
        decoded.id() == 'cmd-legacy'
        decoded.status() == CommandStatus.PROCESSING
        decoded.params() instanceof CreateJobParams
        decoded.params().image == 'alpine:latest'
        and: 'new fields default without a stored value — safe rolling deploy'
        decoded.errorsCount() == 0
        decoded.modifiedAt() == null
        and: 'a payload never carries a version — pre-versioning entries read as 0 (see VersionAware)'
        decoded.version() == 0
    }

    def 'should decode the legacy status names written before the WorkQueue rename'() {
        given: '''the encoder as wired by CommandStateStoreFactory, and state persisted by a replica
                  that still called the statuses SUBMITTED and RUNNING — during a rolling deploy, or
                  before it, for as long as the stored state lives (state-ttl)'''
        def encoder = new JacksonEncodingStrategy<CommandState>() {}
        def json = """\
            {
                "id": "cmd-wire",
                "type": "create-job",
                "status": "${legacy}",
                "params": null,
                "result": null,
                "error": null,
                "errorsCount": 0,
                "createdAt": "${Instant.now()}",
                "startedAt": null,
                "modifiedAt": null,
                "completedAt": null
            }""".stripIndent()

        when:
        def decoded = encoder.decode(json)

        then: 'the @JsonAlias mapping carries it onto the current name — removing it strands the entry'
        decoded.status() == current

        where:
        legacy      || current
        'SUBMITTED' || CommandStatus.PENDING
        'RUNNING'   || CommandStatus.PROCESSING
    }

    def 'version should never leak into the serialized payload'() {
        given: 'the encoder as wired by CommandStateStoreFactory, and a state carrying a version'
        def encoder = new JacksonEncodingStrategy<CommandState>() {}
        def state = CommandState.create('cmd-ver', 'test', null).withVersion(42)

        when:
        def json = encoder.encode(state)

        then: 'the version travels in the store frame, not the payload — the frame is the single source of truth'
        !json.contains('"version"')
        and: 'decoding yields version 0 until the store injects the frame version'
        encoder.decode(json).version() == 0
    }
}
