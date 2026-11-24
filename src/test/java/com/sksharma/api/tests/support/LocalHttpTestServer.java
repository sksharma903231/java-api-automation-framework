package com.sksharma.api.tests.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reusable local HTTP test server backed by JDK {@link HttpServer}.
 * Binds to an ephemeral OS-assigned port (127.0.0.1:0), records all incoming HTTP requests
 * in a thread-safe manner, and dispatches responses based on configurable routes and stubs.
 */
public final class LocalHttpTestServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalHttpTestServer.class);
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final HttpServer server;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<CapturedRequest> capturedRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, HttpHandler> customHandlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StubResponse> stubResponses = new ConcurrentHashMap<>();

    private LocalHttpTestServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
        this.server.createContext("/", this::handleRootExchange);
    }

    /**
     * Creates and starts a new LocalHttpTestServer on an ephemeral port.
     *
     * @return running LocalHttpTestServer instance
     * @throws IOException if server fails to bind
     */
    public static LocalHttpTestServer createAndStart() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(LOOPBACK_HOST, 0), 0);
        ExecutorService executorService = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "local-http-test-server-worker");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(executorService);
        LocalHttpTestServer testServer = new LocalHttpTestServer(httpServer, executorService);
        httpServer.start();
        LOG.info("LocalHttpTestServer started at {}", testServer.getBaseUrl());
        return testServer;
    }

    /**
     * Returns the base URL of the local server (e.g., http://127.0.0.1:54321).
     */
    public String getBaseUrl() {
        return "http://" + LOOPBACK_HOST + ":" + getPort();
    }

    /**
     * Returns the ephemeral port assigned to this server.
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * Registers a custom handler for a given path.
     */
    public void registerHandler(String path, HttpHandler handler) {
        customHandlers.put(path, handler);
    }

    /**
     * Registers a controlled stub response for a given path.
     */
    public void registerResponse(String path, StubResponse stub) {
        stubResponses.put(path, stub);
    }

    /**
     * Registers a JSON stub response for a given path.
     */
    public void registerJsonResponse(String path, int statusCode, String jsonBody) {
        registerResponse(path, StubResponse.builder()
                .statusCode(statusCode)
                .contentType("application/json")
                .body(jsonBody)
                .build());
    }

    /**
     * Registers a JSON stub response with a delay.
     */
    public void registerJsonResponse(String path, int statusCode, String jsonBody, Duration delay) {
        registerResponse(path, StubResponse.builder()
                .statusCode(statusCode)
                .contentType("application/json")
                .body(jsonBody)
                .delay(delay)
                .build());
    }

    /**
     * Registers an empty stub response (e.g. for HTTP 204 No Content).
     */
    public void registerEmptyResponse(String path, int statusCode) {
        registerResponse(path, StubResponse.builder()
                .statusCode(statusCode)
                .body(new byte[0])
                .build());
    }

    /**
     * Registers a text/html or custom content type stub response.
     */
    public void registerTextResponse(String path, int statusCode, String contentType, String body) {
        registerResponse(path, StubResponse.builder()
                .statusCode(statusCode)
                .contentType(contentType)
                .body(body)
                .build());
    }

    /**
     * Resets all registered custom handlers and stub responses.
     */
    public void resetRoutes() {
        customHandlers.clear();
        stubResponses.clear();
    }

    /**
     * Returns a snapshot list of all captured requests in chronological order.
     */
    public List<CapturedRequest> getCapturedRequests() {
        return new ArrayList<>(capturedRequests);
    }

    /**
     * Returns the most recent captured request, or null if no requests were received.
     */
    public CapturedRequest getLastRequest() {
        CapturedRequest last = null;
        for (CapturedRequest req : capturedRequests) {
            last = req;
        }
        return last;
    }

    /**
     * Returns all captured requests that match the given path.
     */
    public List<CapturedRequest> getRequestsForPath(String path) {
        List<CapturedRequest> matching = new ArrayList<>();
        for (CapturedRequest req : capturedRequests) {
            if (path.equals(req.getPath())) {
                matching.add(req);
            }
        }
        return matching;
    }

    /**
     * Clears all recorded requests.
     */
    public void clearCapturedRequests() {
        capturedRequests.clear();
    }

    /**
     * Stops the HTTP server and terminates the executor pool.
     */
    public synchronized void stop() {
        try {
            server.stop(0);
        } catch (Exception e) {
            LOG.warn("Exception stopping HttpServer: {}", e.getMessage());
        }
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            LOG.warn("Exception shutting down executor: {}", e.getMessage());
        }
        LOG.info("LocalHttpTestServer stopped");
    }

    @Override
    public void close() {
        stop();
        clearCapturedRequests();
        resetRoutes();
    }

    private void handleRootExchange(HttpExchange exchange) {
        try {
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            CapturedRequest request = CapturedRequest.of(exchange, bodyBytes);
            capturedRequests.add(request);

            String path = exchange.getRequestURI().getPath();

            // 1. Check custom handler
            HttpHandler customHandler = findCustomHandler(path);
            if (customHandler != null) {
                customHandler.handle(exchange);
                return;
            }

            // 2. Check stub response
            StubResponse stub = findStubResponse(path);
            if (stub != null) {
                serveStub(exchange, stub);
                return;
            }

            // 3. Fallback: 404 Not Found
            byte[] notFoundBytes = "{\"error\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, notFoundBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFoundBytes);
            }
        } catch (IOException e) {
            LOG.debug("I/O error handling exchange (client may have disconnected): {}", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    private HttpHandler findCustomHandler(String path) {
        HttpHandler handler = customHandlers.get(path);
        if (handler != null) {
            return handler;
        }
        for (Map.Entry<String, HttpHandler> entry : customHandlers.entrySet()) {
            if (!entry.getKey().equals("/") && path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private StubResponse findStubResponse(String path) {
        StubResponse stub = stubResponses.get(path);
        if (stub != null) {
            return stub;
        }
        for (Map.Entry<String, StubResponse> entry : stubResponses.entrySet()) {
            if (!entry.getKey().equals("/") && path.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void serveStub(HttpExchange exchange, StubResponse stub) throws IOException {
        if (stub.getDelay() != null && !stub.getDelay().isZero()) {
            try {
                Thread.sleep(stub.getDelay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (stub.getContentType() != null) {
            exchange.getResponseHeaders().set("Content-Type", stub.getContentType());
        }
        for (Map.Entry<String, String> header : stub.getHeaders().entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }

        byte[] respBody = stub.getBody();
        if (stub.getStatusCode() == 204 || respBody.length == 0) {
            exchange.sendResponseHeaders(stub.getStatusCode(), -1);
        } else {
            exchange.sendResponseHeaders(stub.getStatusCode(), respBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBody);
            }
        }
    }

    /**
     * Immutable snapshot of an HTTP request captured by the local test server.
     */
    public static final class CapturedRequest {
        private final String method;
        private final URI uri;
        private final String path;
        private final String rawQuery;
        private final Map<String, List<String>> queryParams;
        private final Map<String, List<String>> headers;
        private final byte[] bodyBytes;
        private final String bodyText;

        private CapturedRequest(String method, URI uri, String path, String rawQuery,
                                Map<String, List<String>> queryParams,
                                Map<String, List<String>> headers,
                                byte[] bodyBytes, String bodyText) {
            this.method = method;
            this.uri = uri;
            this.path = path;
            this.rawQuery = rawQuery;
            this.queryParams = queryParams;
            this.headers = headers;
            this.bodyBytes = bodyBytes != null ? bodyBytes.clone() : new byte[0];
            this.bodyText = bodyText;
        }

        static CapturedRequest of(HttpExchange exchange, byte[] bodyBytes) {
            URI requestUri = exchange.getRequestURI();
            String rawQuery = requestUri.getRawQuery();
            Map<String, List<String>> queryParams = parseQueryParams(rawQuery);

            Map<String, List<String>> headerMap = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                headerMap.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }

            String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);

            return new CapturedRequest(
                    exchange.getRequestMethod(),
                    requestUri,
                    requestUri.getPath(),
                    rawQuery,
                    Collections.unmodifiableMap(queryParams),
                    Collections.unmodifiableMap(headerMap),
                    bodyBytes,
                    bodyText
            );
        }

        private static Map<String, List<String>> parseQueryParams(String rawQuery) {
            Map<String, List<String>> params = new LinkedHashMap<>();
            if (rawQuery == null || rawQuery.isBlank()) {
                return params;
            }
            String[] pairs = rawQuery.split("&");
            for (String pair : pairs) {
                if (pair.isEmpty()) {
                    continue;
                }
                int idx = pair.indexOf('=');
                String key;
                String val;
                if (idx >= 0) {
                    key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    val = (idx < pair.length() - 1)
                            ? URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                            : "";
                } else {
                    key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                    val = "";
                }
                params.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
            }
            return params;
        }

        public String getMethod() {
            return method;
        }

        public URI getUri() {
            return uri;
        }

        public String getPath() {
            return path;
        }

        public String getRawQuery() {
            return rawQuery;
        }

        public Map<String, List<String>> getQueryParams() {
            return queryParams;
        }

        public String getFirstQueryParam(String name) {
            List<String> values = queryParams.get(name);
            return (values != null && !values.isEmpty()) ? values.get(0) : null;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public String getFirstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        public byte[] getBodyBytes() {
            return bodyBytes.clone();
        }

        public String getBodyText() {
            return bodyText;
        }
    }

    /**
     * Configurable stub response definition.
     */
    public static final class StubResponse {
        private final int statusCode;
        private final String contentType;
        private final byte[] body;
        private final Map<String, String> headers;
        private final Duration delay;

        private StubResponse(int statusCode, String contentType, byte[] body,
                             Map<String, String> headers, Duration delay) {
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.body = body != null ? body.clone() : new byte[0];
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.delay = delay;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getBody() {
            return body.clone();
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public Duration getDelay() {
            return delay;
        }

        public static final class Builder {
            private int statusCode = 200;
            private String contentType = "application/json";
            private byte[] body = new byte[0];
            private final Map<String, String> headers = new LinkedHashMap<>();
            private Duration delay = Duration.ZERO;

            public Builder statusCode(int statusCode) {
                this.statusCode = statusCode;
                return this;
            }

            public Builder contentType(String contentType) {
                this.contentType = contentType;
                return this;
            }

            public Builder body(String bodyText) {
                this.body = bodyText != null ? bodyText.getBytes(StandardCharsets.UTF_8) : new byte[0];
                return this;
            }

            public Builder body(byte[] bodyBytes) {
                this.body = bodyBytes != null ? bodyBytes.clone() : new byte[0];
                return this;
            }

            public Builder header(String name, String value) {
                this.headers.put(name, value);
                return this;
            }

            public Builder delay(Duration delay) {
                this.delay = delay != null ? delay : Duration.ZERO;
                return this;
            }

            public StubResponse build() {
                return new StubResponse(statusCode, contentType, body, headers, delay);
            }
        }
    }
}
