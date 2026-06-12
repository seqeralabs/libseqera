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
package io.seqera.util.tsid

import io.seqera.nodeid.NodeId
import spock.lang.Specification

class TsidNodeConfigurerTest extends Specification {

    def cleanup() {
        System.clearProperty('tsidcreator.node')
        System.clearProperty('tsidcreator.node.count')
    }

    def 'should set the tsid node system property from the node id'() {
        given:
        def nodeId = Mock(NodeId) { value() >> 7; capacity() >> 1024 }
        def configurer = new TsidNodeConfigurer(nodeId: nodeId)

        when:
        configurer.init()

        then:
        System.getProperty('tsidcreator.node') == '7'
        and: 'node count is left at the tsid default when capacity is 1024'
        System.getProperty('tsidcreator.node.count') == null
    }

    def 'should set the node count when capacity differs from the tsid default'() {
        given:
        def nodeId = Mock(NodeId) { value() >> 3; capacity() >> 256 }
        def configurer = new TsidNodeConfigurer(nodeId: nodeId)

        when:
        configurer.init()

        then:
        System.getProperty('tsidcreator.node') == '3'
        System.getProperty('tsidcreator.node.count') == '256'
    }
}
