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

import groovy.transform.Canonical
import groovy.transform.ToString

/**
 * Model a security key record associated with a registered service endpoint.
 *
 * <p>The optional {@code token} is the license token the remote service
 * presented when it opened the pairing session (for {@code tower} pairings this
 * is the SHA-256 checksum of the Enterprise license). It is captured so that a
 * request whose declared endpoint resolves to this record can be validated
 * against the license binding held by the license manager. It is {@code null}
 * for records created before this field existed, or when the remote service
 * paired without a token.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Canonical
@ToString(excludes = 'privateKey')
class PairingRecord {
    String service
    String endpoint
    String pairingId
    byte[] privateKey
    byte[] publicKey
    Instant expiration
    String token

    boolean isExpiredAt(Instant time) {
        return expiration == null || expiration.isBefore(time)
    }

    boolean isExpired() {
        return isExpiredAt(Instant.now())
    }
}
