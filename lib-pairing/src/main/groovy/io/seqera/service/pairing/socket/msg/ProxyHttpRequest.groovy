/*
 * Copyright 2026, Seqera Labs
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

package io.seqera.service.pairing.socket.msg

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import io.seqera.util.string.StringUtils

/**
 * Model a remote HTTP request send via WebSocket connection
 *
 * @author Jordi Deu-Pons <jordi@seqera.io>
 */
@Canonical
@CompileStatic
class ProxyHttpRequest implements PairingMessage {
    String msgId
    String method
    String uri
    String auth
    String body
    Map<String, List<String>> headers

    @Override
    String toString() {
        return "ProxyHttpRequest(msgId:$msgId, method:$method, uri:$uri, auth:${StringUtils.redact(auth)}, body:${StringUtils.redact(body)}, headers:${MessageRedact.headers(headers)})"
    }
}
