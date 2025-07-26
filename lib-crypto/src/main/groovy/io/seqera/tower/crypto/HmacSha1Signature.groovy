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

package io.seqera.tower.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import groovy.transform.CompileStatic


/**
 * https://developer.github.com/webhooks/securing/#validating-payloads-from-github
 */
@CompileStatic
class HmacSha1Signature {

    private final static String HMAC_SHA1_ALGORITHM = "HmacSHA1"

    private HmacSha1Signature() {}

    static String compute(String data, String secret) {
        SecretKeySpec signingKey = new SecretKeySpec(secret.bytes, HMAC_SHA1_ALGORITHM)
        return computeSha1(signingKey, data)
    }

    static String compute(String data, byte[] secret) {
        SecretKeySpec signingKey = new SecretKeySpec(secret, HMAC_SHA1_ALGORITHM)
        return computeSha1(signingKey, data)
    }

    private static String computeSha1(SecretKeySpec signingKey, String data) {
        Mac mac = Mac.getInstance(HMAC_SHA1_ALGORITHM)
        mac.init(signingKey)

        return mac.doFinal(data.bytes).encodeHex()
    }

}
