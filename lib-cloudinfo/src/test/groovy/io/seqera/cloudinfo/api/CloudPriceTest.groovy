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

/**
 * Decodes the AZ ID that cloudinfo 0.26.0 attaches to every AWS spot price
 * (seqeralabs/cloudinfo#101).
 */
class CloudPriceTest extends Specification {

    private static final JacksonEncodingStrategy<CloudProduct> PRODUCT =
            new JacksonEncodingStrategy<CloudProduct>() {}

    def 'decodes the zone ID of each spot price, null when the backend omits it'() {
        given:
        def json = '{"type":"m5.large","spotPrice":[' +
                '{"zone":"eu-west-1a","zoneId":"euw1-az2","price":0.0412},' +
                '{"zone":"eu-west-1b","price":0.0398}]}'

        when:
        def prices = PRODUCT.decode(json).spotPrice

        then:
        prices[0].zone == 'eu-west-1a'
        prices[0].zoneId == 'euw1-az2'
        prices[1].zone == 'eu-west-1b'
        prices[1].zoneId == null
    }

    def 'equals and hashCode include the zone ID'() {
        given:
        def a = new CloudPrice(price: 0.04f, zone: 'eu-west-1a', zoneId: 'euw1-az2')
        def b = new CloudPrice(price: 0.04f, zone: 'eu-west-1a', zoneId: 'euw1-az2')
        def c = new CloudPrice(price: 0.04f, zone: 'eu-west-1a', zoneId: 'euw1-az1')

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }
}
