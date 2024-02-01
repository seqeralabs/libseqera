/*
 * Copyright 2023, Seqera Labs
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

package io.seqera.wave.api

import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class SubmitContainerTokenRequestTest extends Specification {

    def 'should copy a request' () {
        given:
        def req = new SubmitContainerTokenRequest(
                towerAccessToken: 'a1',
                towerRefreshToken: 'a2',
                towerEndpoint: 'a3',
                towerWorkspaceId: 4,
                containerImage: 'a5',
                containerFile:  'a6',
                containerConfig: new ContainerConfig(entrypoint: ['this','that']),
                condaFile: 'a8',
                spackFile: 'a9',
                spackTarget: 'a10',
                containerPlatform: 'a11',
                buildRepository: 'a12',
                cacheRepository: 'a13',
                timestamp: 'a14',
                fingerprint: 'a15',
                freeze: true,
                format: 'sif',
                dryRun: true,
                workflowId: 'id123'
        )

        when:
        def copy = req.copyWith(Map.of())
        then:
        copy.towerAccessToken == req.towerAccessToken
        copy.towerRefreshToken == req.towerRefreshToken
        copy.towerEndpoint == req.towerEndpoint
        copy.towerWorkspaceId == req.towerWorkspaceId
        copy.containerImage == req.containerImage
        copy.containerFile == req.containerFile
        copy.containerConfig == req.containerConfig
        copy.condaFile == req.condaFile
        copy.spackFile == req.spackFile
        copy.spackTarget == req.spackTarget
        copy.containerPlatform == req.containerPlatform
        copy.buildRepository == req.buildRepository
        copy.cacheRepository == req.cacheRepository
        copy.timestamp == req.timestamp
        copy.fingerprint == req.fingerprint
        copy.freeze == req.freeze
        copy.format == req.format
        copy.dryRun == req.dryRun
        copy.workflowId == req.workflowId
        and:
        copy.formatSingularity()

        when:
        def other = req.copyWith(
                towerAccessToken: 'b1',
                towerRefreshToken: 'b2',
                towerEndpoint: 'b3',
                towerWorkspaceId: 44,
                containerImage: 'b5',
                containerFile:  'b6',
                containerConfig: new ContainerConfig(entrypoint: ['foo','bar']),
                condaFile: 'b8',
                spackFile: 'b9',
                spackTarget: 'b10',
                containerPlatform: 'b11',
                buildRepository: 'b12',
                cacheRepository: 'b13',
                timestamp: 'b14',
                fingerprint: 'b15',
                freeze: false,
                format: 'foo',
                dryRun: false,
                workflowId: 'id123'
        )
        then:
        other.towerAccessToken == 'b1'
        other.towerRefreshToken == 'b2'
        other.towerEndpoint == 'b3'
        other.towerWorkspaceId == 44
        other.containerImage == 'b5'
        other.containerFile == 'b6'
        other.containerConfig == new ContainerConfig(entrypoint: ['foo','bar'])
        other.condaFile == 'b8'
        other.spackFile == 'b9'
        other.spackTarget == 'b10'
        other.containerPlatform == 'b11'
        other.buildRepository == 'b12'
        other.cacheRepository == 'b13'
        other.timestamp == 'b14'
        other.fingerprint == 'b15'
        other.freeze == false
        other.format == 'foo'
        other.dryRun == false
        other.workflowId == 'id123'
        and:
        !other.formatSingularity()
    }

}
