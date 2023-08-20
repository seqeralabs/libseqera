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

package io.seqera.wave.util

import spock.lang.Specification
/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ZipUtilsTest extends Specification {

    def 'should compress/decompress a text'() {
        given:
        def TEXT = 'Hello world\n' * 10

        when:
        def buffer = ZipUtils.compress(TEXT)
        then:
        buffer.size() > 0 && buffer.size() < TEXT.bytes.size()

        when:
        def copy = ZipUtils.decompressAsString(buffer)
        then:
        copy == TEXT

        when:
        def bytes = ZipUtils.decompressAsBytes(buffer)
        then:
        bytes == TEXT.bytes
    }

}
