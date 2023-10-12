/*
 * Copyright 2023, Seqera Labs
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
