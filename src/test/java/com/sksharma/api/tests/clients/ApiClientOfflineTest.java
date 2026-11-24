package com.sksharma.api.tests.clients;

import com.sksharma.api.clients.ApiClient;
import com.sksharma.api.config.ConfigManager;
import com.sksharma.api.tests.support.LocalHttpTestServer;
import com.sksharma.api.tests.support.LocalHttpTestServer.CapturedRequest;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.exception.JsonPathException;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic offline test suite for {@link ApiClient}.
 * Validates HTTP execution against a locally controlled JDK HttpServer without external network dependencies.
 */
@Epic("Core Framework")
@Feature("Offline HTTP Client")
@Test(groups = "offline")
public class ApiClientOfflineTest {

    private LocalHttpTestServer server;
    private ApiClient apiClient;

    @BeforeClass(alwaysRun = true)
    public void setupClass() throws IOException {
        server = LocalHttpTestServer.createAndStart();
        System.setProperty("base.url", server.getBaseUrl());
        ConfigManager.reset();
        apiClient = new ApiClient();
    }

    @AfterClass(alwaysRun = true)
    public void tearDownClass() {
        if (server != null) {
            server.stop();
        }
        System.clearProperty("base.url");
        System.clearProperty("http.timeout.seconds");
        System.clearProperty("logging.include.body");
        ConfigManager.reset();
    }

    @BeforeMethod(alwaysRun = true)
    public void resetServerState() {
        if (server != null) {
            server.clearCapturedRequests();
            server.resetRoutes();
        }
        System.setProperty("base.url", server.getBaseUrl());
        System.clearProperty("http.timeout.seconds");
        ConfigManager.reset();

        // Enforce that offline tests never inherit an external DummyJSON base URL
        assertThat(ConfigManager.getInstance().getBaseUrl())
                .as("Offline tests must never inherit an external URL")
                .startsWith("http://127.0.0.1:")
                .doesNotContain("dummyjson.com");
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupProperties() {
        if (server != null) {
            System.setProperty("base.url", server.getBaseUrl());
        }
        System.clearProperty("http.timeout.seconds");
        System.clearProperty("logging.include.body");
        ConfigManager.reset();
    }

    // ==========================================
    // 1. HTTP Methods Tests
    // ==========================================

    @Test(description = "Verify GET method execution and server request capture")
    @Story("HTTP Methods")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Executes GET request via ApiClient and asserts response code, JSON body, and server-side captured method and path.")
    public void testHttpGetMethod() {
        server.registerJsonResponse("/api/test-get", 200, "{\"status\":\"ok\",\"method\":\"GET\"}");

        Response response = apiClient.get("/api/test-get");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("status")).isEqualTo("ok");
        assertThat(response.jsonPath().getString("method")).isEqualTo("GET");

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getMethod()).isEqualTo("GET");
        assertThat(captured.getPath()).isEqualTo("/api/test-get");
        assertThat(captured.getBodyText()).isEmpty();
    }

    @Test(description = "Verify POST method execution with JSON payload and 201 response")
    @Story("HTTP Methods")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Executes POST request via ApiClient with JSON payload, asserting 201 Created and server-side payload capture.")
    public void testHttpPostMethod() {
        server.registerJsonResponse("/api/test-post", 201, "{\"id\":1001,\"status\":\"created\"}");
        Map<String, Object> payload = Map.of("name", "Widget", "price", 19.99);

        Response response = apiClient.post("/api/test-post", payload);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getInt("id")).isEqualTo(1001);
        assertThat(response.jsonPath().getString("status")).isEqualTo("created");

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getMethod()).isEqualTo("POST");
        assertThat(captured.getPath()).isEqualTo("/api/test-post");
        assertThat(captured.getBodyText())
                .contains("\"name\":\"Widget\"")
                .contains("19.99");
        assertThat(captured.getFirstHeader("Content-Type")).contains("application/json");
    }

    @Test(description = "Verify PUT method execution replacing a resource")
    @Story("HTTP Methods")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes PUT request via ApiClient replacing an existing resource, asserting 200 response and server payload capture.")
    public void testHttpPutMethod() {
        server.registerJsonResponse("/api/resource/42", 200, "{\"id\":42,\"name\":\"Updated Widget\"}");
        Map<String, Object> payload = Map.of("id", 42, "name", "Updated Widget");

        Response response = apiClient.put("/api/resource/42", payload);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("name")).isEqualTo("Updated Widget");

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getMethod()).isEqualTo("PUT");
        assertThat(captured.getPath()).isEqualTo("/api/resource/42");
        assertThat(captured.getBodyText()).contains("\"name\":\"Updated Widget\"");
    }

    @Test(description = "Verify PATCH method execution updating a specific field")
    @Story("HTTP Methods")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes PATCH request via ApiClient updating specific fields, asserting 200 response and server payload capture.")
    public void testHttpPatchMethod() {
        server.registerJsonResponse("/api/resource/42", 200, "{\"id\":42,\"status\":\"patched\"}");
        Map<String, Object> payload = Map.of("status", "patched");

        Response response = apiClient.patch("/api/resource/42", payload);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("status")).isEqualTo("patched");

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getMethod()).isEqualTo("PATCH");
        assertThat(captured.getPath()).isEqualTo("/api/resource/42");
        assertThat(captured.getBodyText()).contains("\"status\":\"patched\"");
    }

    @Test(description = "Verify DELETE method execution simulating resource removal")
    @Story("HTTP Methods")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes DELETE request via ApiClient, asserting 200 response and server method capture.")
    public void testHttpDeleteMethod() {
        server.registerJsonResponse("/api/resource/42", 200, "{\"deleted\":true}");

        Response response = apiClient.delete("/api/resource/42");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getBoolean("deleted")).isTrue();

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getMethod()).isEqualTo("DELETE");
        assertThat(captured.getPath()).isEqualTo("/api/resource/42");
    }

    // ==========================================
    // 2. Request Handling Tests
    // ==========================================

    @Test(description = "Verify query parameters transmission and server-side capture")
    @Story("Request Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Sends multiple query parameters via ApiClient.get and asserts server receives and decodes all parameters.")
    public void testQueryParameters() {
        server.registerJsonResponse("/api/search", 200, "{\"total\":2}");
        Map<String, Object> queryParams = Map.of(
                "query", "automation framework",
                "limit", 25,
                "active", true
        );

        Response response = apiClient.get("/api/search", queryParams);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getInt("total")).isEqualTo(2);

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getPath()).isEqualTo("/api/search");
        assertThat(captured.getFirstQueryParam("query")).isEqualTo("automation framework");
        assertThat(captured.getFirstQueryParam("limit")).isEqualTo("25");
        assertThat(captured.getFirstQueryParam("active")).isEqualTo("true");
    }

    @Test(description = "Verify custom headers transmission via custom RequestSpecification")
    @Story("Request Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Sends custom tracking and correlation headers via custom RequestSpecification and asserts server capture.")
    public void testCustomHeaders() {
        server.registerJsonResponse("/api/headers", 200, "{\"headersReceived\":true}");
        RequestSpecification customSpec = new RequestSpecBuilder()
                .addHeader("X-Correlation-ID", "corr-abc-123")
                .addHeader("X-Tenant-ID", "tenant-alpha")
                .build();

        Response response = apiClient.get("/api/headers", customSpec);

        assertThat(response.getStatusCode()).isEqualTo(200);

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getFirstHeader("X-Correlation-ID")).isEqualTo("corr-abc-123");
        assertThat(captured.getFirstHeader("X-Tenant-ID")).isEqualTo("tenant-alpha");
    }

    @Test(description = "Verify JSON request body serialization and Content-Type header")
    @Story("Request Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Sends complex structured JSON payload and validates body structure and Content-Type header on server.")
    public void testJsonRequestBody() {
        server.registerJsonResponse("/api/payload", 200, "{\"accepted\":true}");
        Map<String, Object> payload = Map.of(
                "module", "test-runner",
                "tags", List.of("unit", "offline"),
                "priority", 1
        );

        Response response = apiClient.post("/api/payload", payload);

        assertThat(response.getStatusCode()).isEqualTo(200);

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getFirstHeader("Content-Type")).contains("application/json");
        assertThat(captured.getBodyText())
                .contains("\"module\":\"test-runner\"")
                .contains("\"priority\":1");
    }

    @Test(description = "Verify default Content-Type and Accept headers configuration")
    @Story("Request Handling")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that default RequestSpecification enforces application/json for Accept and Content-Type headers.")
    public void testContentTypeAndAcceptHeaders() {
        server.registerJsonResponse("/api/content-negotiation", 200, "{\"negotiated\":true}");

        apiClient.get("/api/content-negotiation");
        CapturedRequest getRequest = server.getLastRequest();
        assertThat(getRequest).isNotNull();
        assertThat(getRequest.getFirstHeader("Accept")).contains("application/json");

        apiClient.post("/api/content-negotiation", Map.of("key", "value"));
        CapturedRequest postRequest = server.getLastRequest();
        assertThat(postRequest).isNotNull();
        assertThat(postRequest.getFirstHeader("Content-Type")).contains("application/json");
    }

    @Test(description = "Verify URL and endpoint construction with leading, non-leading, and nested paths")
    @Story("Request Handling")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies URI path construction for endpoints with leading slash, without leading slash, and nested paths.")
    public void testUrlAndEndpointConstruction() {
        server.registerJsonResponse("/api/endpoint-slash", 200, "{\"path\":\"slash\"}");
        server.registerJsonResponse("/api/endpoint-noslash", 200, "{\"path\":\"noslash\"}");
        server.registerJsonResponse("/api/v1/nested/resource/sub", 200, "{\"path\":\"nested\"}");

        apiClient.get("/api/endpoint-slash");
        assertThat(server.getLastRequest().getPath()).isEqualTo("/api/endpoint-slash");

        apiClient.get("api/endpoint-noslash");
        assertThat(server.getLastRequest().getPath()).isEqualTo("/api/endpoint-noslash");

        apiClient.get("/api/v1/nested/resource/sub");
        assertThat(server.getLastRequest().getPath()).isEqualTo("/api/v1/nested/resource/sub");
    }

    // ==========================================
    // 3. Response Handling Tests
    // ==========================================

    @Test(description = "Verify HTTP 200 OK response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies ApiClient correctly processes HTTP 200 OK responses with JSON body.")
    public void testResponseStatus200Ok() {
        server.registerJsonResponse("/api/status/200", 200, "{\"status\":200,\"message\":\"OK\"}");

        Response response = apiClient.get("/api/status/200");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("message")).isEqualTo("OK");
    }

    @Test(description = "Verify HTTP 201 Created response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies ApiClient correctly processes HTTP 201 Created responses.")
    public void testResponseStatus201Created() {
        server.registerJsonResponse("/api/status/201", 201, "{\"status\":201,\"created\":true}");

        Response response = apiClient.post("/api/status/201", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getBoolean("created")).isTrue();
    }

    @Test(description = "Verify HTTP 204 No Content response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies ApiClient correctly processes HTTP 204 No Content responses with an empty body.")
    public void testResponseStatus204NoContent() {
        server.registerEmptyResponse("/api/status/204", 204);

        Response response = apiClient.delete("/api/status/204");

        assertThat(response.getStatusCode()).isEqualTo(204);
        assertThat(response.asString()).isEmpty();
    }

    @Test(description = "Verify HTTP 400 Bad Request error response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies ApiClient correctly processes HTTP 400 Bad Request error payloads.")
    public void testResponseStatus400BadRequest() {
        server.registerJsonResponse("/api/status/400", 400, "{\"error\":\"Bad Request\",\"code\":400}");

        Response response = apiClient.get("/api/status/400");

        assertThat(response.getStatusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Bad Request");
    }

    @Test(description = "Verify HTTP 401 Unauthorized error response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies ApiClient correctly processes HTTP 401 Unauthorized error responses.")
    public void testResponseStatus401Unauthorized() {
        server.registerJsonResponse("/api/status/401", 401, "{\"error\":\"Unauthorized\",\"message\":\"Invalid token\"}");

        Response response = apiClient.get("/api/status/401");

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Unauthorized");
    }

    @Test(description = "Verify HTTP 404 Not Found error response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies ApiClient correctly processes HTTP 404 Not Found error responses.")
    public void testResponseStatus404NotFound() {
        server.registerJsonResponse("/api/status/404", 404, "{\"error\":\"Not Found\",\"resource\":\"user\"}");

        Response response = apiClient.get("/api/status/404");

        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Not Found");
    }

    @Test(description = "Verify HTTP 500 Internal Server Error response handling")
    @Story("Response Handling")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies ApiClient correctly processes HTTP 500 Internal Server Error responses.")
    public void testResponseStatus500InternalServerError() {
        server.registerJsonResponse("/api/status/500", 500, "{\"error\":\"Internal Server Error\"}");

        Response response = apiClient.get("/api/status/500");

        assertThat(response.getStatusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Internal Server Error");
    }

    // ==========================================
    // 4. Error and Edge-Case Handling Tests
    // ==========================================

    @Test(description = "Verify slow server response exceeding configured timeout triggers socket timeout exception")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Configures a 2.5-second server delay with a 1-second client timeout and asserts SocketTimeoutException is raised.")
    public void testSlowServerResponseAndConfiguredTimeout() {
        server.registerJsonResponse("/api/timeout", 200, "{\"status\":\"delayed\"}", Duration.ofMillis(2500));
        System.setProperty("http.timeout.seconds", "1");
        ConfigManager.reset();

        assertThatThrownBy(() -> apiClient.get("/api/timeout"))
                .satisfies(throwable -> {
                    boolean isTimeout = throwable instanceof SocketTimeoutException
                            || (throwable.getCause() instanceof SocketTimeoutException);
                    assertThat(isTimeout)
                            .as("Expected SocketTimeoutException directly or as cause, got %s", throwable.getClass().getName())
                            .isTrue();
                });
    }

    @Test(description = "Verify malformed JSON response body handling")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that non-JSON body returns 200 OK, preserves raw body text, and fails gracefully when parsed as JSON.")
    public void testMalformedJsonResponse() {
        String invalidJson = "INVALID_JSON_BODY { not_a_json : [ 1, 2, ";
        server.registerTextResponse("/api/malformed", 200, "application/json", invalidJson);

        Response response = apiClient.get("/api/malformed");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(invalidJson);
        assertThatThrownBy(() -> response.jsonPath().getString("key"))
                .isInstanceOf(JsonPathException.class);
    }

    @Test(description = "Verify empty response body handling")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that a 200 OK response with an empty body is handled correctly without deserialization failure.")
    public void testEmptyResponseBody() {
        server.registerEmptyResponse("/api/empty-body", 200);

        Response response = apiClient.get("/api/empty-body");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.asString()).isEmpty();
    }

    @Test(description = "Verify unexpected content type response handling (HTML maintenance page)")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that unexpected Content-Type (text/html) is preserved and readable via asString().")
    public void testUnexpectedContentType() {
        String htmlBody = "<!DOCTYPE html><html><body>502 Bad Gateway - Proxy Error</body></html>";
        server.registerTextResponse("/api/html-error", 502, "text/html; charset=UTF-8", htmlBody);

        Response response = apiClient.get("/api/html-error");

        assertThat(response.getStatusCode()).isEqualTo(502);
        assertThat(response.getContentType()).contains("text/html");
        assertThat(response.asString()).contains("502 Bad Gateway");
    }

    @Test(description = "Verify connection failure when connecting to unreachable host / closed port")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that attempting to connect to a closed ephemeral port raises a connection exception.")
    public void testConnectionFailureOnUnreachableHost() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        System.setProperty("base.url", "http://127.0.0.1:" + closedPort);
        ConfigManager.reset();

        assertThatThrownBy(() -> apiClient.get("/api/unreachable"))
                .satisfies(throwable -> {
                    boolean isConnect = throwable instanceof ConnectException
                            || (throwable.getCause() instanceof ConnectException);
                    assertThat(isConnect)
                            .as("Expected ConnectException directly or as cause, got %s", throwable.getClass().getName())
                            .isTrue();
                });
    }

    // ==========================================
    // 5. Thread Safety Tests
    // ==========================================

    @Test(description = "Verify shared ApiClient thread safety and lack of response cross-contamination under concurrent execution")
    @Story("Concurrency & Thread Safety")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Executes 10 concurrent requests through a single shared ApiClient instance, confirming each worker receives its distinct response without cross-thread contamination.")
    public void testConcurrentExecutionThreadSafety() throws InterruptedException {
        int threadCount = 10;
        server.registerHandler("/api/concurrent", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String workerId = "unknown";
            if (query != null && query.startsWith("workerId=")) {
                workerId = query.substring("workerId=".length());
            }
            byte[] responseBytes = ("{\"workerId\":\"" + workerId + "\",\"status\":\"processed\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<String> receivedWorkerIds = Collections.synchronizedList(new ArrayList<>());
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 1; i <= threadCount; i++) {
            final String currentWorkerId = "worker-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Response resp = apiClient.get("/api/concurrent", Map.of("workerId", currentWorkerId));
                    if (resp.getStatusCode() != 200) {
                        throw new IllegalStateException("Expected 200 but got " + resp.getStatusCode());
                    }
                    String returnedWorkerId = resp.jsonPath().getString("workerId");
                    if (!currentWorkerId.equals(returnedWorkerId)) {
                        throw new IllegalStateException("Cross-thread contamination: expected "
                                + currentWorkerId + " but got " + returnedWorkerId);
                    }
                    receivedWorkerIds.add(returnedWorkerId);
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completedInTime = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).as("All concurrent requests must complete within timeout").isTrue();
        assertThat(errors).as("No threads should encounter errors during concurrent execution").isEmpty();
        assertThat(receivedWorkerIds).hasSize(threadCount);
        for (int i = 1; i <= threadCount; i++) {
            assertThat(receivedWorkerIds).contains("worker-" + i);
        }

        List<CapturedRequest> captured = server.getRequestsForPath("/api/concurrent");
        assertThat(captured).hasSize(threadCount);
    }
}
