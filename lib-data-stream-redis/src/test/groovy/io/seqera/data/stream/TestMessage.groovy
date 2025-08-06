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

package io.seqera.data.stream

import groovy.transform.Canonical
import io.seqera.data.stream.impl.StreamMessage
import io.seqera.serde.encode.StringEncodingStrategy
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Canonical
class TestMessage implements StreamMessage {
    String x
    String y

    public static final String TOPIC_ID = "test-task-event"

    public static final StringEncodingStrategy<TestMessage> ENCODING_STRATEGY = new StringEncodingStrategy<TestMessage>() {
        @Override
        String encode(TestMessage message) {
            return new JsonBuilder([x: message.x, y: message.y]).toString()
        }

        @Override
        TestMessage decode(String encoded) {
            def json = new JsonSlurper().parseText(encoded)
            return new TestMessage(json.x, json.y)
        }
    }
    
    @Override
    String getTopicId() {
        return TOPIC_ID
    }
    
    @Override
    StringEncodingStrategy getEncodingStrategy() {
        return ENCODING_STRATEGY
    }
}
