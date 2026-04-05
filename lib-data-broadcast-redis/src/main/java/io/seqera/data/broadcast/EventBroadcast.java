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

package io.seqera.data.broadcast;

import java.util.List;
import java.util.function.Consumer;

/**
 * Event broadcast with pub/sub semantics and replay support.
 *
 * <p>Events are buffered per key so late-connecting clients receive
 * the full history. Active clients receive new events via push callbacks.
 *
 * <p>Unlike a work queue (where each message is consumed by one consumer),
 * a broadcast delivers every event to all registered clients.
 *
 * @param <T> the event type
 * @author Paolo Di Tommaso
 */
public interface EventBroadcast<T> {

    /**
     * Offer a new event for the given key.
     * Buffers the event and pushes it to all connected clients.
     */
    void offer(String key, T event);

    /**
     * Register a client for events on the given key.
     * Immediately delivers all buffered events, then pushes new events as they arrive.
     *
     * @param key      the key to subscribe to
     * @param clientId unique client identifier
     * @param callback consumer for delivering events
     */
    void registerClient(String key, String clientId, Consumer<T> callback);

    /**
     * Unregister a client.
     */
    void unregisterClient(String key, String clientId);

    /**
     * Get all buffered events for a key.
     */
    List<T> getBufferedEvents(String key);

    /**
     * Clean up buffers and clients for a key.
     */
    void cleanup(String key);
}
