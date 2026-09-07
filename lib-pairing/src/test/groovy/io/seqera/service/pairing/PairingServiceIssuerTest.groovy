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

import java.time.Duration

import spock.lang.Specification

import io.seqera.data.store.state.impl.LocalStateProvider

/**
 * Tests that the token issuer presented at pairing time is recorded on the
 * {@link PairingRecord} and refreshed on re-pair.
 */
class PairingServiceIssuerTest extends Specification {

    static final String SERVICE = PairingService.TOWER_SERVICE
    static final String ENDPOINT = 'https://api.cloud.seqera.io'
    static final String ISSUER = 'https://cloud.seqera.io/api'

    /**
     * A real {@link PairingStore} over the in-memory {@link LocalStateProvider}, rather than a
     * Spock mock: {@code PairingStore} is a concrete class, and class proxying via cglib fails
     * on this JDK ("Unsupported class file major version"). Using the real store also exercises
     * the Moshi round-trip on every put/get, so a field the encoder cannot handle would surface
     * here rather than only in production.
     */
    PairingStore store = new PairingStore(new LocalStateProvider()).tap { it.config = new StubConfig() }
    PairingServiceImpl service = new PairingServiceImpl(store: store, config: new StubConfig())

    def 'should record the issuer presented at pairing time'() {
        when:
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1', ISSUER)

        then:
        with(service.getPairingRecord(SERVICE, ENDPOINT)) {
            issuer == ISSUER
            token == 'lic-1'
            endpoint == ENDPOINT
            and: 'the issuer is not the endpoint — on Cloud these genuinely differ'
            issuer != endpoint
        }
    }

    def 'should leave the issuer null when the client does not send one'() {
        when: 'a client predating the param pairs through the 3-arg overload'
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1')

        then:
        service.getPairingRecord(SERVICE, ENDPOINT).issuer == null
    }

    def 'should refresh the issuer on re-pair without waiting for expiry'() {
        given:
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1', ISSUER)
        final pairingId = service.getPairingRecord(SERVICE, ENDPOINT).pairingId

        when: 'the same service re-pairs announcing a different issuer'
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1', 'https://cloud.eu.seqera.io/api')
        final after = service.getPairingRecord(SERVICE, ENDPOINT)

        then: 'the issuer is updated and the key pair is NOT regenerated'
        after.issuer == 'https://cloud.eu.seqera.io/api'
        after.pairingId == pairingId
    }

    def 'should not erase a recorded issuer when a later pair omits it'() {
        given:
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1', ISSUER)

        when: 'a re-pair sends no issuer — e.g. a rolled-back client'
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1', null)

        then: 'the known issuer is retained rather than nulled out'
        service.getPairingRecord(SERVICE, ENDPOINT).issuer == ISSUER
    }

    def 'should refresh token and issuer together'() {
        given:
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-1', ISSUER)

        when:
        service.acquirePairingKey(SERVICE, ENDPOINT, 'lic-2', 'https://other.example.com/api')

        then:
        with(service.getPairingRecord(SERVICE, ENDPOINT)) {
            token == 'lic-2'
            issuer == 'https://other.example.com/api'
        }
    }

    static class StubConfig implements PairingConfig {
        @Override Duration getKeyLease() { Duration.ofDays(1) }
        @Override Duration getKeyDuration() { Duration.ofDays(30) }
        @Override Duration getChannelTimeout() { Duration.ofSeconds(5) }
        @Override Duration getChannelAwaitTimeout() { Duration.ofMillis(100) }
        @Override boolean getCloseSessionOnInvalidLicenseToken() { false }
        @Override List<String> getDenyHosts() { [] }
    }
}
