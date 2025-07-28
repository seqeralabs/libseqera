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

package io.seqera.random;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Generate the unique key randomly of `Long` type
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class LongRndKey {

    private static final int LEN = 6;
    private static final BigInteger B = BigInteger.ONE.shiftLeft(8 * LEN);

    private static final SecureRandom random = new SecureRandom();

    private LongRndKey() {}

    static BigInteger rnd0() {
        while( true ) {
            byte[] buffer = new byte[LEN];
            random.nextBytes(buffer);
            BigInteger big = new BigInteger(buffer);
            if (big.signum() < 0) {
                big = big.add(B);
            }
            else if( big.signum()==0 ) {
                continue;
            }

            return big;
        }

    }
    /**
     * Generate Long random key guaranteed to be unique for a range of keys < 1 million
     *
     * @return A random generated long number > 0
     */
    static public Long rndLong() {
        return rnd0().longValue();
    }

    static public String rndLongAsString() {
        StringBuilder result = new StringBuilder();
        result.append(rndLong());
        while( result.length()<15 )
            result.insert(0, "0");
        return result.toString();
    }

    static public String rndHex() {
        StringBuilder result = new StringBuilder();
        result.append(rnd0().toString(16));
        while( result.length()<12 )
            result.insert(0, "0");
        return result.toString();
    }

}
