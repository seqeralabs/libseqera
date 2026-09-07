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

package io.seqera.service.pairing

import java.time.Instant

import spock.lang.Specification
import spock.lang.Unroll

import io.seqera.serde.moshi.MoshiEncodeStrategy

/**
 * Tests for {@link PairingRecord} serialization
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class PairingRecordSerializationTest extends Specification {

    def 'should serialize and deserialize PairingRecord'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def expiration = Instant.parse('2025-06-15T10:30:00Z')
        def record = new PairingRecord(
                'tower',
                'https://tower.example.com',
                'pairing-123',
                'private-key-data'.bytes,
                'public-key-data'.bytes,
                expiration
        )

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.service == 'tower'
        decoded.endpoint == 'https://tower.example.com'
        decoded.pairingId == 'pairing-123'
        decoded.privateKey == 'private-key-data'.bytes
        decoded.publicKey == 'public-key-data'.bytes
        decoded.expiration == expiration
    }

    def 'should handle null expiration'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def record = new PairingRecord('tower', 'endpoint', 'id', new byte[0], new byte[0], null)

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.expiration == null
    }

    def 'should serialize and deserialize the token issuer'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def record = new PairingRecord(
                'tower',
                'https://api.cloud.seqera.io',
                'pairing-123',
                'private-key-data'.bytes,
                'public-key-data'.bytes,
                Instant.parse('2025-06-15T10:30:00Z'),
                'checksum-abc',
                'https://cloud.seqera.io/api'
        )

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then: 'the issuer round-trips, and is distinct from the endpoint'
        decoded.issuer == 'https://cloud.seqera.io/api'
        decoded.endpoint == 'https://api.cloud.seqera.io'
        decoded.issuer != decoded.endpoint
    }

    def 'should decode a record written before the issuer field existed'() {
        given: 'a record encoded WITH an issuer, then stripped of it — the shape a pre-issuer release wrote'
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def full = encoder.encode(new PairingRecord(
                'tower', 'https://tower.example.com', 'pairing-123',
                'private-key-data'.bytes, 'public-key-data'.bytes,
                Instant.parse('2025-06-15T10:30:00Z'), 'checksum-abc', 'https://tower.example.com/api'))
        // derived rather than hand-written so the byte[] encoding stays whatever Moshi actually uses
        def json = full.replaceAll(/,?"issuer":"[^"]*"/, '')
        assert !json.contains('issuer')

        when:
        def decoded = encoder.decode(json)

        then: 'it still decodes, with a null issuer rather than an error'
        decoded.service == 'tower'
        decoded.token == 'checksum-abc'
        decoded.issuer == null
    }

    def 'should serialize and deserialize the license token'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def record = new PairingRecord(
                'tower',
                'https://tower.example.com',
                'pairing-123',
                'private-key-data'.bytes,
                'public-key-data'.bytes,
                Instant.parse('2025-06-15T10:30:00Z'),
                'checksum-abc'
        )

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.token == 'checksum-abc'
    }

    def 'should default the token to null for records paired without one'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        // a record constructed without the token (e.g. persisted before the field existed)
        def record = new PairingRecord('tower', 'endpoint', 'id', new byte[0], new byte[0], Instant.now())

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.token == null
    }

    def 'should handle empty byte arrays'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def record = new PairingRecord('tower', 'endpoint', 'id', new byte[0], new byte[0], Instant.now())

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.privateKey.length == 0
        decoded.publicKey.length == 0
    }

    @Unroll
    def 'should preserve field values - service=#service, endpoint=#endpoint'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def record = new PairingRecord(service, endpoint, pairingId, new byte[0], new byte[0], Instant.now())

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.service == service
        decoded.endpoint == endpoint
        decoded.pairingId == pairingId

        where:
        service   | endpoint                        | pairingId
        'tower'   | 'https://tower.example.com'     | 'pair-001'
        'platform'| 'https://cloud.seqera.io'       | 'pair-002'
        'custom'  | 'http://localhost:8080'         | 'pair-003'
    }

    def 'should serialize binary key data correctly'() {
        given:
        def encoder = new MoshiEncodeStrategy<PairingRecord>() {}
        def privateKey = new byte[256]
        def publicKey = new byte[294]
        new Random(42).nextBytes(privateKey)
        new Random(43).nextBytes(publicKey)
        def record = new PairingRecord('tower', 'endpoint', 'id', privateKey, publicKey, Instant.now())

        when:
        def json = encoder.encode(record)
        def decoded = encoder.decode(json)

        then:
        decoded.privateKey == privateKey
        decoded.publicKey == publicKey
    }
}
