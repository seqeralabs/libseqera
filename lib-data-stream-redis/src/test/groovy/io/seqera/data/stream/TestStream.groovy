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

import java.time.Duration

import io.seqera.serde.encode.StringEncodingStrategy
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TestStream extends AbstractMessageStream<TestMessage> {

    private String groupName

    TestStream(MessageStream<String> target) {
        super(target)
        this.groupName = "seqera-message-stream" // default
    }

    TestStream(MessageStream<String> target, String groupName) {
        super(target)
        this.groupName = groupName
    }

    @Override
    protected String consumerGroupName() {
        return groupName
    }

    @Override
    protected StringEncodingStrategy<TestMessage> createEncodingStrategy() {
        return new StringEncodingStrategy<TestMessage>() {
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
    }

    @Override
    protected String name() {
        return 'test-stream'
    }

    @Override
    protected Duration pollInterval() {
        return Duration.ofSeconds(1)
    }
}
