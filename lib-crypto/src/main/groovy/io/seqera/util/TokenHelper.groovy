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

package io.seqera.util

import java.security.SecureRandom

import groovy.transform.CompileStatic

/**
 * Helper class to create secure random tokens
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class TokenHelper {

    /**
     * Create secure cryptographic random tokens
     *
     * See https://stackoverflow.com/a/44227131/395921
     *
     * @return A 40 hex-characters random string 
     */
    static String createHexToken() {
        final secureRandom = new SecureRandom()
        byte[] token = new byte[20]
        secureRandom.nextBytes(token)
        def result = new BigInteger(1, token).toString(16)
        // pad with extra zeros if necessary
        while( result.size()<40 )
            result = '0'+result
        return result
    }

}
