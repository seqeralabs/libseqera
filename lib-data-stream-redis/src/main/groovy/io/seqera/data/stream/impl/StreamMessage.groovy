package io.seqera.data.stream.impl

import io.seqera.serde.encode.StringEncodingStrategy

interface StreamMessage {

    String getTopicId()
    StringEncodingStrategy getEncodingStrategy()

}
