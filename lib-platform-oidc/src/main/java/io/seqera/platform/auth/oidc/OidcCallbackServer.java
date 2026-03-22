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
 */

package io.seqera.platform.auth.oidc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpServer;

/**
 * A lightweight local HTTP server that listens for the OAuth2 authorization callback
 * on {@code 127.0.0.1} with an ephemeral port.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class OidcCallbackServer implements AutoCloseable {

    private static final String SUCCESS_HTML =
            "<html><body><h2>Login successful! You can close this tab.</h2></body></html>";
    private static final String ERROR_HTML =
            "<html><body><h2>Authentication failed: %s</h2></body></html>";

    private final HttpServer server;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
    private final String expectedState;

    /**
     * Create and start the callback server.
     *
     * @param expectedState the OAuth2 state parameter to validate
     * @throws IOException if the server cannot bind
     */
    public OidcCallbackServer(String expectedState) throws IOException {
        this.expectedState = expectedState;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/callback", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI());
            byte[] responseBody;
            int status;

            String error = params.get("error");
            if (error != null) {
                String desc = params.getOrDefault("error_description", error);
                responseBody = String.format(ERROR_HTML, desc).getBytes(StandardCharsets.UTF_8);
                status = 400;
                codeFuture.completeExceptionally(new IOException("Authentication error: " + desc));
            }
            else if (!expectedState.equals(params.get("state"))) {
                responseBody = String.format(ERROR_HTML, "state mismatch").getBytes(StandardCharsets.UTF_8);
                status = 400;
                codeFuture.completeExceptionally(new IOException("OAuth state mismatch"));
            }
            else {
                String code = params.get("code");
                if (code == null || code.isEmpty()) {
                    responseBody = String.format(ERROR_HTML, "missing authorization code").getBytes(StandardCharsets.UTF_8);
                    status = 400;
                    codeFuture.completeExceptionally(new IOException("Callback missing authorization code"));
                }
                else {
                    responseBody = SUCCESS_HTML.getBytes(StandardCharsets.UTF_8);
                    status = 200;
                    codeFuture.complete(code);
                }
            }

            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(status, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }
        });
        this.server.start();
    }

    /**
     * @return the port the server is listening on
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * @return the full redirect URI for this server
     */
    public String getRedirectUri() {
        return "http://127.0.0.1:" + getPort() + "/callback";
    }

    /**
     * Block until the authorization code is received or timeout expires.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the authorization code
     * @throws Exception if the callback fails, times out, or is interrupted
     */
    public String waitForCode(long timeout, TimeUnit unit) throws Exception {
        try {
            return codeFuture.get(timeout, unit);
        }
        catch (TimeoutException e) {
            throw new IOException("Authentication timed out waiting for browser callback");
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new LinkedHashMap<>();
        String query = uri.getQuery();
        if (query == null) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                map.put(key, value);
            }
        }
        return map;
    }
}
