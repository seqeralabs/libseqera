/*
 * Copyright 2024, Seqera Labs
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

import java.security.SecureRandom

import groovy.transform.CompileStatic

/**
 * Generate a secure random key
 *
 * Inspired to https://neilmadden.blog/2018/08/30/moving-away-from-uuids/
 */
@CompileStatic
class SecureRandomKey {

    protected static final SecureRandom random = new SecureRandom();
    protected static final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding()
    protected static final Base64.Decoder decoder = Base64.getUrlDecoder()

    static String generate() {
        byte[] buffer = new byte[16]
        random.nextBytes(buffer)
        return encoder.encodeToString(buffer)
    }

    static byte[] fromString(String key) {
        decoder.decode(key)
    }

}
