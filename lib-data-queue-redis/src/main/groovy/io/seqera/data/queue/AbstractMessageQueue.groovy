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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.micronaut.websocket.exceptions.WebSocketSessionException
import io.seqera.serde.encode.StringEncodingStrategy
import io.seqera.util.retry.ExponentialAttempt
import jakarta.annotation.PostConstruct
/**
 * Abstract base implementation of a distributed message queue that supports client registration
 * and automatic message dispatch to connected WebSocket clients.
 * 
 * <p>This class implements a messaging system where:</p>
 * <ul>
 *   <li>Multiple clients can register to receive messages from the same target queue</li>
 *   <li>Each message is delivered to exactly one available client (load balancing)</li>
 *   <li>Failed message deliveries are automatically retried</li>
 *   <li>Disconnected clients are detected and excluded from message delivery</li>
 *   <li>Message serialization/deserialization is handled transparently</li>
 * </ul>
 * 
 * <p>The implementation uses a background thread to continuously poll for messages and
 * dispatch them to registered clients. This provides push-based message delivery while
 * maintaining the reliability of queue-based messaging.</p>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li><strong>Client Management:</strong> Automatic registration/unregistration of message consumers</li>
 *   <li><strong>Load Balancing:</strong> Messages distributed across available clients</li>
 *   <li><strong>Fault Tolerance:</strong> Automatic retry and client failure detection</li>
 *   <li><strong>Serialization:</strong> Pluggable encoding strategies for message formats</li>
 *   <li><strong>Monitoring:</strong> Built-in metrics and error reporting</li>
 * </ul>
 * 
 * <p>Usage pattern:</p>
 * <pre>{@code
 * // Subclass implementation
 * public class MyMessageQueue extends AbstractMessageQueue<MyMessage> {
 *     protected StringEncodingStrategy<MyMessage> createEncodingStrategy() {
 *         return new JsonEncodingStrategy<>() {};
 *     }
 *     
 *     protected String name() { return "my-queue"; }
 *     protected Duration pollInterval() { return Duration.ofSeconds(1); }
 *     protected String prefix() { return "myapp:"; }
 * }
 * 
 * // Register a WebSocket client
 * queue.registerClient("notifications", clientId, message -> {
 *     webSocketSession.send(message);
 * });
 * 
 * // Send a message (will be delivered to one of the registered clients)
 * queue.offer("notifications", new MyMessage("Hello World"));
 * }</pre>
 *
 * @param <M> the type of messages that can be sent through the queue
 * 
 * @author Jordi Deu-Pons <jordi@seqera.io>
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 * @since 1.0
 * @see MessageQueue
 * @see MessageSender
 */
@Slf4j
@CompileStatic
abstract class AbstractMessageQueue<M> implements Runnable {

    final private static AtomicInteger count = new AtomicInteger()

    final private MessageQueue<String> broker

    final private StringEncodingStrategy<M> encoder

    final private ExponentialAttempt attempt = new ExponentialAttempt()

    final private Thread thread

    final private ConcurrentHashMap<String,MessageSender<String>> clients = new ConcurrentHashMap<>()

    final private String name0

    final private Cache<String,Boolean> closedClients

    AbstractMessageQueue(MessageQueue<String> broker, ExecutorService ioExecutor) {
        this.encoder = createEncodingStrategy()
        this.broker = broker
        this.closedClients = createCache(ioExecutor)
        this.name0 = name() + '-thread-' + count.getAndIncrement()
        this.thread = new Thread(this, name0)
        this.thread.setDaemon(true)
    }

    private Cache<String,Boolean> createCache(ExecutorService ioExecutor) {
        Caffeine
                .newBuilder()
                .executor(ioExecutor)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build()
    }

    /**
     * Creates an instance of the encoding strategy used to serialize and deserialize messages.
     * 
     * <p>This method must be implemented by subclasses to provide the appropriate serialization
     * strategy for the message type. The strategy will be used to convert messages to strings
     * for storage in the underlying queue and back to objects when delivering to clients.</p>
     * 
     * @return a new instance of the encoding strategy; must not be null
     * @see StringEncodingStrategy
     */
    abstract protected StringEncodingStrategy<M> createEncodingStrategy()

    /**
     * Start the listener thread after the class creation, to avoid race-condition accessing attributes
     * initialised during the class creation.
     *
     * This method does not need to be invoked directly, other than for testing purposes
     *
     * @return The object queue itself.  
     */
    @PostConstruct
    protected AbstractMessageQueue<M> start() {
        thread.start()
        return this
    }

    protected abstract String name()

    protected abstract Duration pollInterval()

    protected abstract String prefix()

    protected String targetKey(String k) {
        return prefix() + k
    }

    protected String clientKey(String target, String clientId) {
        return targetKey(target) + ":client=$clientId/queue"
    }

    protected String targetFromClientKey(String clientKey) {
        final p = clientKey.indexOf(':client=')
        if( p==-1 ) throw new IllegalArgumentException("Invalid client key: '$clientKey'")
        return clientKey.substring(0,p)
    }

    /**
     * Adds a message to the specified target queue for delivery to registered clients.
     * 
     * <p>The message will be serialized using the configured encoding strategy and added
     * to the underlying message broker. Any registered client for the target queue may
     * receive and process this message. Messages are delivered in FIFO order and each
     * message will be delivered to exactly one client.</p>
     * 
     * <p>This method is thread-safe and can be called concurrently from multiple threads.</p>
     *
     * @param target the name of the target queue; must not be null or empty
     * @param message the message to be queued for delivery; may be null depending on encoding strategy
     */
    void offer(String target, M message) {
        // serialise the message to a string
        final serialized = encoder.encode(message)
        // add the message the target queue
        // NOTE: all clients connecting from the same endpoint
        // will use the *same* target queue name
        // Any register sender having matching target key
        // will be able to take and send the message
        broker.offer(targetKey(target), serialized)
    }

    /**
     * Registers a client to receive messages from the specified target queue.
     * 
     * <p>Multiple clients can be registered for the same target, creating a load-balanced
     * message delivery system where each message is delivered to exactly one available client.
     * The client will remain registered until explicitly unregistered or until a delivery
     * failure indicates the client has disconnected.</p>
     * 
     * <p>The provided {@link MessageSender} will be called whenever a message needs to be
     * delivered to this client. Messages are automatically deserialized before delivery.</p>
     * 
     * <p>Registration is thread-safe and can be performed while the queue is actively
     * processing messages.</p>
     *
     * @param target the target queue name that this client wants to receive messages from;
     *               must not be null or empty
     * @param clientId a unique identifier for this client instance; must not be null or empty
     * @param sender the message sender that will handle delivery to this client; must not be null
     * @see #unregisterClient(String, String)
     */
    void registerClient(String target, String clientId, MessageSender<M> sender) {
        clients.put(clientKey(target,clientId), new MessageSender<String>() {
            @Override
            void send(String message) {
                final decodeMessage = encoder.decode(message)
                sender.send(decodeMessage)
            }
        })
    }

    /**
     * Unregisters a previously registered client from receiving messages.
     * 
     * <p>After unregistration, the client will no longer receive any messages from the
     * specified target queue. This method should be called when a client disconnects
     * or no longer needs to receive messages.</p>
     * 
     * <p>Unregistration is thread-safe and can be performed while the queue is actively
     * processing messages. If the client is not currently registered, this method has
     * no effect and returns normally.</p>
     *
     * @param target the target queue name that the client was registered for;
     *               must not be null or empty
     * @param clientId the unique identifier of the client to unregister;
     *                 must not be null or empty
     * @see #registerClient(String, String, MessageSender)
     */
    void unregisterClient(String target, String clientId) {
        clients.remove(clientKey(target,clientId))
    }

    @Override
    void run() {
        final clientsCache = closedClients
        while( !thread.isInterrupted() ) {
            try {
                int sent=0
                final clients = new HashMap<String,MessageSender<String>>(this.clients)
                for( Map.Entry<String,MessageSender<String>> entry : clients ) {
                    // ignore clients marked as closed
                    if( clientsCache.getIfPresent(entry.key))
                        continue
                    // infer the target queue from the client key
                    final target = targetFromClientKey(entry.key)
                    // poll for a message from the queue
                    final value = broker.poll(target)
                    // if there's a message try to send it
                    if( value != null ) {
                        try {
                            entry.value.send(value)
                            sent++
                        }
                        catch (WebSocketSessionException e) {
                            log.warn "Unable to send message ${value} - cause: ${e.message}"
                            // it could not manage to send the event
                            // offer back the value to be processed again
                            broker.offer(target, value)
                            if( e.message?.contains('close') ) {
                                clientsCache.put(entry.key, true)
                            }
                        }
                    }
                }
                // reset the attempt count because no error has been thrown
                attempt.reset()
                // if no message was sent, sleep for a while before retrying
                if( sent==0 ) {
                    sleep(pollInterval().toMillis())
                }
            }
            catch (InterruptedException e) {
                log.debug "Interrupting spooler thread for queue ${name0}"
                Thread.currentThread().interrupt()
                break
            }
            catch (Throwable e) {
                final d0 = attempt.delay()
                log.error("Unexpected error on queue ${name0} (await: ${d0}) - cause: ${e.message}", e)
                sleep(d0.toMillis())
            }
        }
    }

    void close() {
        if( !thread )
            return
        // interrupt the thread
        thread.interrupt()
        // wait for the termination
        try {
            thread.join(1_000)
        }
        catch (Exception e) {
            log.debug "Unexpected error while terminating ${name0} - cause: ${e.message}"
        }
    }
}
