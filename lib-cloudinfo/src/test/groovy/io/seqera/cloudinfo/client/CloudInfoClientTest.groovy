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

package io.seqera.cloudinfo.client

import spock.lang.Specification
import spock.lang.Shared

/**
 * Integration tests for CloudInfoClient
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class CloudInfoClientTest extends Specification {

    @Shared
    CloudInfoClient client = CloudInfoClient.create()

    def 'should fetch regions'() {
        when:
        def regions = client.getRegions('amazon')

        then:
        regions.size() > 0
        regions.any { it.id == 'us-east-1' }
    }

    def 'should fetch region ids'() {
        when:
        def ids = client.getRegionIds('amazon')

        then:
        ids.size() > 0
        ids.contains('us-east-1')
    }

    def 'should fetch products'() {
        when:
        def products = client.getProducts('amazon', 'us-east-1')

        then:
        products.size() > 0
        products.every { it.type != null }
        products.any { it.onDemandPrice > 0 }
    }
}
