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

package io.seqera.http

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal authenticating forward proxy for integration tests.
 *
 * <p>Answers 407 with a {@code Proxy-Authenticate: Basic} challenge when the request
 * carries no (or wrong) {@code Proxy-Authorization} header. When the expected Basic
 * credentials are supplied:
 * <ul>
 *   <li>plain requests (absolute-URI form) are answered directly with a configurable
 *       200 response - the proxy does not forward to any upstream server</li>
 *   <li>{@code CONNECT} requests are answered with {@code 200 Connection established}
 *       and the socket is then closed, which is enough to observe the tunnelling
 *       authentication handshake (the client's TLS handshake fails afterwards)</li>
 * </ul>
 *
 * <p>All received request lines and their {@code Proxy-Authorization} headers are recorded
 * for assertions.
 */
class MockAuthProxyServer implements Closeable {

    final String username
    final String password

    String responseBody = 'OK via proxy'
    String responseContentType = 'text/plain'

    final List<String> requestLines = new CopyOnWriteArrayList<>()
    final List<String> authHeaders = new CopyOnWriteArrayList<>()  // 'NONE' when the header was absent

    private ServerSocket server
    private Thread acceptor

    MockAuthProxyServer(String username, String password) {
        this.username = username
        this.password = password
    }

    int start() {
        server = new ServerSocket(0, 50, InetAddress.getByName('127.0.0.1'))
        acceptor = Thread.startDaemon('mock-proxy-acceptor') {
            while (!server.isClosed()) {
                Socket socket
                try {
                    socket = server.accept()
                }
                catch (IOException e) {
                    break
                }
                Thread.startDaemon('mock-proxy-worker') { handle(socket) }
            }
        }
        return server.localPort
    }

    int getPort() {
        return server.localPort
    }

    String expectedAuthHeader() {
        return 'Basic ' + Base64.encoder.encodeToString("${username}:${password}".getBytes('UTF-8'))
    }

    @Override
    void close() {
        server?.close()
    }

    private void handle(Socket socket) {
        try {
            socket.soTimeout = 10_000
            final input = socket.inputStream
            final output = socket.outputStream

            final requestLine = readLine(input)
            if (!requestLine)
                return
            final headers = [:] as Map<String, String>
            String line
            while ((line = readLine(input))) {
                final sep = line.indexOf(':')
                if (sep > 0)
                    headers[line.substring(0, sep).trim().toLowerCase()] = line.substring(sep + 1).trim()
            }
            // consume the request body, if any, before answering
            final contentLength = (headers['content-length'] ?: '0') as int
            for (int i = 0; i < contentLength; i++)
                input.read()

            requestLines << requestLine
            final auth = headers['proxy-authorization']
            authHeaders << (auth ?: 'NONE')

            if (auth != expectedAuthHeader()) {
                output.write(('HTTP/1.1 407 Proxy Authentication Required\r\n'
                        + 'Proxy-Authenticate: Basic realm="test"\r\n'
                        + 'Content-Length: 0\r\n'
                        + 'Connection: close\r\n'
                        + '\r\n').getBytes('UTF-8'))
                output.flush()
                return
            }

            if (requestLine.startsWith('CONNECT')) {
                // acknowledge the tunnel then close - enough to observe the CONNECT auth handshake
                output.write('HTTP/1.1 200 Connection established\r\n\r\n'.getBytes('UTF-8'))
                output.flush()
                return
            }

            final body = responseBody.getBytes('UTF-8')
            output.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: ${responseContentType}\r\n"
                    + "Content-Length: ${body.length}\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes('UTF-8'))
            output.write(body)
            output.flush()
        }
        catch (IOException e) {
            // client disconnected - ignore
        }
        finally {
            try { socket.close() } catch (IOException e) { }
        }
    }

    private static String readLine(InputStream input) {
        final buffer = new ByteArrayOutputStream()
        int ch
        while ((ch = input.read()) != -1) {
            if (ch == '\n' as char)
                break
            if (ch != '\r' as char)
                buffer.write(ch)
        }
        return buffer.toString('UTF-8')
    }
}
