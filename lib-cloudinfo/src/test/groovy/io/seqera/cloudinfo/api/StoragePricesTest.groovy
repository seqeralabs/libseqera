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
 * Decodes cloudinfo 0.25.0 /storage payloads (seqeralabs/cloudinfo#95). The
 * fixtures under src/test/resources/cloudinfo follow the wire shape of
 * cloudinfo's StoragePrices (omitempty fields left out), with values from its
 * live-captured test data.
 */
class StoragePricesTest extends Specification {

    private static final JacksonEncodingStrategy<StoragePrices> ENCODER =
            new JacksonEncodingStrategy<StoragePrices>() {}

    private static StoragePrices load(String fixture) {
        ENCODER.decode(StoragePricesTest.getResourceAsStream("/cloudinfo/${fixture}").text)
    }

    private static VolumePrice volume(StoragePrices prices, String type) {
        prices.volumes.find { it.volumeType == type }
    }

    def 'decodes amazon EBS prices with the gp3 free baseline'() {
        when:
        def prices = load('storage-amazon-us-east-1.json')
        def gp3 = volume(prices, 'gp3')
        def io2 = volume(prices, 'io2')

        then:
        prices.source == 'aws-pricing-api'
        prices.scrapingTime == '1790000000000'
        prices.volumes*.volumeType == ['gp2', 'gp3', 'io1', 'io2', 'standard']

        and:
        gp3.storageMedia == 'SSD-backed'
        gp3.pricePerGBMonth == 0.08d
        gp3.pricePerIopsMonth == 0.005d
        gp3.pricePerMiBpsMonth == 0.04d
        gp3.includedIops == 3000L
        gp3.includedThroughputMiBps == 125L
        gp3.maxIops == 80000L
        gp3.maxThroughputMiBps == 2000L
        gp3.maxSizeGiB == 65536L
        gp3.tiers == null
        gp3.familyPricesPerGBMonth == null

        and: 'io2 IOPS cannot be reduced to one rate, so it stays null rather than 0'
        io2.pricePerGBMonth == 0.125d
        io2.pricePerIopsMonth == null
        io2.includedIops == null
    }

    def 'decodes azure per-disk tiers and transaction prices'() {
        when:
        def prices = load('storage-azure-eastus.json')
        def premium = volume(prices, 'Premium_LRS')
        def p10 = premium.tiers.find { it.tier == 'P10' }

        then:
        prices.source == 'azure-retail-prices-api'

        and: 'Premium SSD is billed per tier, not per GB'
        premium.pricePerGBMonth == null
        premium.pricePer10kTransactions == null
        premium.maxSizeGiB == 32767L
        premium.tiers*.tier == ['P1', 'P10', 'P80']
        p10.sizeGiB == 128L
        p10.pricePerMonth == 19.71d
        p10.iops == 500L
        p10.throughputMiBps == 95L

        and:
        volume(prices, 'Standard_LRS').pricePer10kTransactions == 0.0005d
        volume(prices, 'PremiumV2_LRS').includedIops == 3000L
        volume(prices, 'PremiumV2_LRS').includedThroughputMiBps == 119L
        volume(prices, 'UltraSSD_LRS').includedIops == null
    }

    def 'decodes google local-ssd per-family prices'() {
        when:
        def prices = load('storage-google-us-central1.json')
        def lssd = volume(prices, 'local-ssd')
        def hdb = volume(prices, 'hyperdisk-balanced')

        then:
        prices.source == 'gcp-cloud-billing-api'
        lssd.pricePerGBMonth == 0.08d
        lssd.familyPricesPerGBMonth['c4'] == 0.16d
        lssd.familyPricesPerGBMonth['z4d-highmem-highlssd'] == 0.1632d
        lssd.familyPricesPerGBMonth.size() == 8

        and: 'a family bundled at no charge keeps an explicit 0, distinct from absent'
        lssd.familyPricesPerGBMonth.containsKey('g4')
        lssd.familyPricesPerGBMonth['g4'] == 0d

        and:
        hdb.includedIops == 3000L
        hdb.includedThroughputMiBps == 140L
        volume(prices, 'hyperdisk-throughput').pricePerIopsMonth == null
        volume(prices, 'pd-ssd').pricePerGBMonth == 0.17d
    }

    def 'equals and hashCode are value based'() {
        given:
        def a = load('storage-azure-eastus.json')
        def b = load('storage-azure-eastus.json')

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != load('storage-google-us-central1.json')
    }
}
