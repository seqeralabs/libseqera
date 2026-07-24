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

    def 'features and family round-trip through Jackson serialisation'() {
        given:
        def product = new CloudProduct()
        product.type = 'p4d.24xlarge'
        product.family = 'p4d'
        product.features = ['a100', 'gpu', 'nvidia', 'sched', 'ssd', 'x86']

        when:
        def json = ENCODER.encode(product)
        def decoded = ENCODER.decode(json)

        then:
        decoded.type == 'p4d.24xlarge'
        decoded.family == 'p4d'
        decoded.features == ['a100', 'gpu', 'nvidia', 'sched', 'ssd', 'x86']
    }

    def 'fractional gpusPerVm survives Jackson decode (does not truncate to 0)'() {
        given: 'a GPU instance reporting a fractional accelerator, e.g. AWS g6f.2xlarge'
        def json = '{"type":"g6f.2xlarge","category":"GPU instance","cpusPerVm":8,"gpusPerVm":0.25}'

        when:
        def decoded = ENCODER.decode(json)

        then:
        decoded.type == 'g6f.2xlarge'
        decoded.gpusPerVm == 0.25f
        decoded.gpusPerVm > 0
    }

    def 'whole and absent gpusPerVm decode correctly'() {
        expect:
        ENCODER.decode('{"type":"p4d.24xlarge","gpusPerVm":8}').gpusPerVm == 8.0f
        ENCODER.decode('{"type":"m5.large","cpusPerVm":2}').gpusPerVm == null
    }

    def 'whole gpusPerVm now serialises as a float (8.0, not 8)'() {
        given:
        def product = new CloudProduct()
        product.type = 'p4d.24xlarge'
        product.gpusPerVm = 8.0f

        expect:
        ENCODER.encode(product).contains('"gpusPerVm":8.0')
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
        def a = new CloudProduct(type: 'm5.large', features: ['sched'])
        def b = new CloudProduct(type: 'm5.large', features: ['sched'])
        def c = new CloudProduct(type: 'm5.large', features: ['sched', 'ssd'])

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }

    def 'equals and hashCode include family'() {
        given:
        def a = new CloudProduct(type: 'm5d.large', family: 'm5d')
        def b = new CloudProduct(type: 'm5d.large', family: 'm5d')
        def c = new CloudProduct(type: 'm5d.large', family: 'm5')

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }

    def 'family defaults to null and an absent wire field deserialises to null'() {
        given: 'a JSON document without the family field'
        def json = '{"type":"m5.large","features":["ssd"]}'

        when:
        def fresh = new CloudProduct()
        def decoded = ENCODER.decode(json)

        then:
        fresh.family == null
        decoded.type == 'm5.large'
        decoded.family == null
        decoded.features == ['ssd']
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
