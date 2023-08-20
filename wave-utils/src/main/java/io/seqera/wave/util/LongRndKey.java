/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.util;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * Generate the unique key in a random manner of `Long` type
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

    static public String rndHex() {
        String result = rnd0().toString(16);
        while( result.length()<12 )
            result = "0" + result;
        return result;
    }

}
