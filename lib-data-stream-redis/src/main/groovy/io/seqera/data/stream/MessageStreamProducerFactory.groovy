package io.seqera.data.stream

import jakarta.inject.Singleton

import groovy.transform.CompileStatic
import io.seqera.data.stream.impl.StreamMessage

@CompileStatic
@Singleton
class MessageStreamProducerFactory {

    private final MessageStream<String> messageStream

    MessageStreamProducerFactory(MessageStream<String> messageStream) {
        this.messageStream = messageStream
    }

    def <T extends StreamMessage> MessageStreamProducer<T> createProducer() {
        return new MessageStreamProducer<T>(messageStream)
    }
}
