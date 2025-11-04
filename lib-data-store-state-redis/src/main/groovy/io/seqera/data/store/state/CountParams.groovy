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

package io.seqera.data.store.state

import groovy.transform.Canonical

/**
 * Model state store auto increment params
 * 
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Canonical
class CountParams {
    final String key
    final String field

    static CountParams of(String key) {
        final p=key.lastIndexOf('/')
        return p==-1
                ? new CountParams("counters/v1", key)
                : new CountParams(key.substring(0,p), key.substring(p+1))
    }

}
