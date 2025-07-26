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

package io.seqera.data.queue

import java.time.Duration
import java.util.concurrent.ExecutorService

import io.seqera.serde.encode.StringEncodingStrategy

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TestMsgQueue extends AbstractMessageQueue<TestMsg> {

    TestMsgQueue(MessageQueue broker, ExecutorService ioExecutor) {
        super(broker, ioExecutor)
    }

    @Override
    protected StringEncodingStrategy<TestMsg> createEncodingStrategy() {
        return new StringEncodingStrategy<TestMsg>() {
            @Override
            String encode(TestMsg value) {
                return "{\"value\":\"${value.value}\"}"
            }
            
            @Override
            TestMsg decode(String value) {
                // Simple JSON parsing for {"value":"text"}
                def startIdx = value.indexOf('"value":"') + 9
                def endIdx = value.lastIndexOf('"')
                def extractedValue = value.substring(startIdx, endIdx)
                return new TestMsg(extractedValue)
            }
        }
    }

    @Override
    protected String name() {
        return 'test-queue'
    }

    @Override
    protected Duration pollInterval() {
        return Duration.ofMillis(100)
    }

    @Override
    protected String prefix() {
        return 'foo:'
    }
}
