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
package io.seqera.data.stream.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.seqera.data.stream.TxContext;

/**
 * Package-private helper shared by {@link RedisMessageStream} and
 * {@link LocalMessageStream}: a {@code TxContext<String>} that lazily
 * allocates its backing list so the overwhelmingly common "no offers"
 * path stays allocation-free.
 */
final class TxContextCollector implements TxContext<String> {

    private List<PendingOffer> offers;

    @Override
    public void offer(String dstStreamId, String payload) {
        if (offers == null) {
            offers = new ArrayList<>(2);
        }
        offers.add(new PendingOffer(dstStreamId, payload));
    }

    List<PendingOffer> collected() {
        return offers == null ? Collections.emptyList() : offers;
    }

    /** Destination stream + payload queued for atomic delivery with the current ACK. */
    record PendingOffer(String streamId, String payload) {}
}
