package io.seqera.data.stream.impl

import io.seqera.serde.encode.StringEncodingStrategy

interface MessageStreamTopic {

    String getTopicId()
    StringEncodingStrategy getEncodingStrategy()

}
