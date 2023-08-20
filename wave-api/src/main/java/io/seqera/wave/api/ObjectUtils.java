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

package io.seqera.wave.api;

import java.util.List;
import java.util.Map;

/**
 * Helper class for object checks
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ObjectUtils {
    static public boolean isEmpty(String value) {
        return value==null || value.length()==0;
    }
    static public boolean isEmpty(Integer value) {
        return value==null || value==0;
    }
    static public boolean isEmpty(Long value) {
        return value==null || value==0;
    }
    static public boolean isEmpty(List value) {
        return value==null || value.size()==0;
    }

    static public boolean isEmpty(Map value) {
        return value==null || value.size()==0;
    }
}
