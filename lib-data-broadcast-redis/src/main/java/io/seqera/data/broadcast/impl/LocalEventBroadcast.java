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

package io.seqera.data.broadcast.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.seqera.data.broadcast.EventBroadcast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory implementation of {@link EventBroadcast}.
 *
 * <p>Uses per-key locks to prevent race conditions between {@link #offer}
 * and {@link #registerClient} — buffered events are replayed before the client
 * is registered, so {@code offer()} cannot double-deliver during replay.
 *
 * <p>Suitable for single-replica deployments and testing.
 *
 * @param <T> the event type
 * @author Paolo Di Tommaso
 */
public class LocalEventBroadcast<T> implements EventBroadcast<T> {

    private static final Logger log = LoggerFactory.getLogger(LocalEventBroadcast.class);

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayList<T>> eventBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Consumer<T>>> clients = new ConcurrentHashMap<>();

    @Override
    public void offer(String key, T event) {
        final Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            eventBuffers.computeIfAbsent(key, k -> new ArrayList<>()).add(event);

            final var keyClients = clients.get(key);
            if (keyClients == null)
                return;
            for (var entry : keyClients.entrySet()) {
                try {
                    entry.getValue().accept(event);
                } catch (Exception e) {
                    log.warn("Failed to deliver event to client {} for key {}: {}", entry.getKey(), key, e.getMessage());
                    keyClients.remove(entry.getKey());
                }
            }
        }
    }

    @Override
    public void registerClient(String key, String clientId, Consumer<T> callback) {
        final Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            final var buffer = eventBuffers.get(key);
            if (buffer != null) {
                for (T event : buffer) {
                    try {
                        callback.accept(event);
                    } catch (Exception e) {
                        log.warn("Failed to deliver buffered event to client {} for key {}: {}", clientId, key, e.getMessage());
                        break;
                    }
                }
            }
            clients.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(clientId, callback);
        }
    }

    @Override
    public void unregisterClient(String key, String clientId) {
        final var keyClients = clients.get(key);
        if (keyClients != null) {
            keyClients.remove(clientId);
            if (keyClients.isEmpty()) {
                clients.remove(key);
            }
        }
    }

    @Override
    public List<T> getBufferedEvents(String key) {
        final Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            final var buffer = eventBuffers.get(key);
            return buffer != null ? List.copyOf(buffer) : List.of();
        }
    }

    @Override
    public void cleanup(String key) {
        eventBuffers.remove(key);
        clients.remove(key);
        locks.remove(key);
    }
}
