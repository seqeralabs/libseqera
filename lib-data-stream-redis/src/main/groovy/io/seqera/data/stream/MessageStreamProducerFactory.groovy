package io.seqera.data.stream

import jakarta.inject.Singleton

import groovy.transform.CompileStatic
import io.seqera.data.stream.impl.MessageStreamTopic

@CompileStatic
@Singleton
class MessageStreamProducerFactory {

    private final MessageStream<String> messageStream

    MessageStreamProducerFactory(MessageStream<String> messageStream) {
        this.messageStream = messageStream
    }

    def <T extends MessageStreamTopic> MessageStreamProducer<T> createProducer() {
        return new MessageStreamProducer<T>(messageStream)
    }
}
