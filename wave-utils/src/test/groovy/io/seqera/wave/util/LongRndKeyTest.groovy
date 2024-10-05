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

package io.seqera.wave.util

import spock.lang.Ignore
import spock.lang.Retry
import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class LongRndKeyTest extends Specification {

    @Retry(count=2)
    def "should generate random long numbers" () {
        expect:
        LongRndKey.rndLong() != LongRndKey.rndLong()
    }

    @Retry(count=2)
    def "should generate random long numbers" () {
        expect:
        LongRndKey.rndLongAsString() != LongRndKey.rndLongAsString()
    }

    def 'should be greater than or equal to zero' () {
        expect:
        10_0000 .times {assert LongRndKey.rndLong() > 0 }
    }

    def 'should return long as string' () {
        expect:
        10_0000 .times {assert LongRndKey.rndLongAsString().size() == 15 }
    }

    def 'should return random hex' () {
        expect:
        10_0000 .times {assert LongRndKey.rndHex().size() == 12 }
    }

    @Ignore
    def 'should be unique' () {
        when:
        def map = new HashMap<String,Boolean>()
        def int c=0
        for( int i=0; i<1_000_000; i++ ) {
            if( i % 100_000 == 0 ) println "Keys ${c++}"
            def key = LongRndKey.rndHex()
            if( map.containsKey(key))
                throw new IllegalArgumentException("Key $key already exists")
            map.put(key, true)
        }
        
        then:
        true
    }
}
