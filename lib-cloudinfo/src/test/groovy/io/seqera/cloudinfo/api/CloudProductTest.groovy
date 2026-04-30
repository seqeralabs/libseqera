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

package io.seqera.cloudinfo.api

import io.seqera.serde.jackson.JacksonEncodingStrategy
import spock.lang.Specification

class CloudProductTest extends Specification {

    private static final JacksonEncodingStrategy<CloudProduct> ENCODER =
            new JacksonEncodingStrategy<CloudProduct>() {}

    def 'features field round-trips through Jackson serialisation'() {
        given:
        def product = new CloudProduct()
        product.type = 'm5d.large'
        product.features = ['SCHED', 'NVME', 'family-type:general-purpose']

        when:
        def json = ENCODER.encode(product)
        def decoded = ENCODER.decode(json)

        then:
        decoded.type == 'm5d.large'
        decoded.features == ['SCHED', 'NVME', 'family-type:general-purpose']
    }

    def 'features field defaults to null on a freshly-constructed product'() {
        when:
        def product = new CloudProduct()

        then:
        product.features == null
    }

    def 'features field accepts an empty list distinct from null'() {
        given:
        def product = new CloudProduct()

        when:
        product.features = []

        then:
        product.features != null
        product.features.isEmpty()
    }

    def 'equals and hashCode include features'() {
        given:
        def a = new CloudProduct(type: 'm5.large', features: ['SCHED'])
        def b = new CloudProduct(type: 'm5.large', features: ['SCHED'])
        def c = new CloudProduct(type: 'm5.large', features: ['SCHED', 'NVME'])

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }

    def 'features absent in the wire format deserialises to null'() {
        given: 'a JSON document from a backend that does not yet emit features'
        def json = '{"type":"m5.large","cpusPerVm":2}'

        when:
        def decoded = ENCODER.decode(json)

        then:
        decoded.type == 'm5.large'
        decoded.cpusPerVm == 2
        decoded.features == null
    }
}
