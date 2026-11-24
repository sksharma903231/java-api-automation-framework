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
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.exception.JsonPathException;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.RequestSpecification;
import com.sksharma.api.clients.RequestSpecFactory;
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

    @Test(description = "Verify connection failure when server abruptly terminates connection without responding")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that when a local server abruptly closes an exchange without headers, an I/O exception is caught cleanly.")
    public void testConnectionFailureOnAbruptServerDisconnection() {
        server.registerHandler("/api/negative/abrupt-close", exchange -> {
            exchange.close();
        });

        assertThatThrownBy(() -> apiClient.get("/api/negative/abrupt-close"))
                .isInstanceOf(IOException.class);
    }

    @Test(description = "Verify unregistered endpoint returns HTTP 404 Not Found")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that requesting an unregistered path returns HTTP 404 without client failure.")
    public void testUnregisteredEndpointReturns404NotFound() {
        Response response = apiClient.get("/api/negative/non-existent-route-404");

        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(response.asString()).isNotNull();
    }

    @Test(description = "Verify HTTP 503 Service Unavailable error response handling")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that ApiClient correctly processes HTTP 503 Service Unavailable responses with error JSON.")
    public void testResponseStatus503ServiceUnavailable() {
        server.registerJsonResponse("/api/negative/503", 503, "{\"error\":\"Service Unavailable\",\"retryAfter\":30}");

        Response response = apiClient.get("/api/negative/503");

        assertThat(response.getStatusCode()).isEqualTo(503);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Service Unavailable");
        assertThat(response.jsonPath().getInt("retryAfter")).isEqualTo(30);
    }

    @Test(description = "Verify HTTP 504 Gateway Timeout error response handling")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that ApiClient correctly processes HTTP 504 Gateway Timeout responses.")
    public void testResponseStatus504GatewayTimeout() {
        server.registerJsonResponse("/api/negative/504", 504, "{\"error\":\"Gateway Timeout\"}");

        Response response = apiClient.get("/api/negative/504");

        assertThat(response.getStatusCode()).isEqualTo(504);
        assertThat(response.jsonPath().getString("error")).isEqualTo("Gateway Timeout");
    }

    @Test(description = "Verify unexpected binary content type response is readable as byte array")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that unexpected binary payload (application/octet-stream) is handled cleanly without corruption.")
    public void testUnexpectedBinaryContentTypeResponse() {
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
        server.registerHandler("/api/negative/binary-data", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, binaryData.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(binaryData);
            }
        });

        Response response = apiClient.get("/api/negative/binary-data");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getContentType()).contains("application/octet-stream");
        assertThat(response.asByteArray()).isEqualTo(binaryData);
    }

    @Test(description = "Verify truncated JSON response body fails gracefully on parsing")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that truncated JSON response returns 200 OK, preserves raw body, and fails gracefully when parsed as JSON.")
    public void testTruncatedJsonResponseHandling() {
        String truncatedJson = "{\"id\":42,\"status\":";
        server.registerTextResponse("/api/negative/truncated-json", 200, "application/json", truncatedJson);

        Response response = apiClient.get("/api/negative/truncated-json");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.asString()).isEqualTo(truncatedJson);
        assertThatThrownBy(() -> response.jsonPath().getInt("id"))
                .isInstanceOf(JsonPathException.class);
    }

    @Test(description = "Verify whitespace-only response body handling")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that a 200 OK response with whitespace-only body is handled gracefully without deserialization crash.")
    public void testWhitespaceOnlyResponseBodyHandling() {
        server.registerTextResponse("/api/negative/whitespace-body", 200, "application/json", "   \t\n   ");

        Response response = apiClient.get("/api/negative/whitespace-body");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.asString().trim()).isEmpty();
    }

    @Test(description = "Verify read timeout during POST request with body")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a POST request with payload triggers SocketTimeoutException when server delay exceeds configured timeout.")
    public void testReadTimeoutDuringPostWithPayload() {
        server.registerJsonResponse("/api/negative/post-timeout", 200, "{\"status\":\"delayed\"}", Duration.ofMillis(2500));
        System.setProperty("http.timeout.seconds", "1");
        ConfigManager.reset();

        assertThatThrownBy(() -> apiClient.post("/api/negative/post-timeout", Map.of("data", "timeout-test")))
                .satisfies(throwable -> {
                    boolean isTimeout = throwable instanceof SocketTimeoutException
                            || (throwable.getCause() instanceof SocketTimeoutException);
                    assertThat(isTimeout)
                            .as("Expected SocketTimeoutException directly or as cause, got %s", throwable.getClass().getName())
                            .isTrue();
                });
    }

    @Test(description = "Verify all HTTP methods enforce destination validation and reject unapproved destinations")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that post, put, patch, and delete all enforce pre-dispatch validation and throw on unapproved destination.")
    public void testAllHttpMethodsRejectUnapprovedDestination() {
        String unapproved = "https://unapproved-blocked-host.com/api/test";
        server.clearCapturedRequests();

        assertThatThrownBy(() -> apiClient.post(unapproved, Map.of("k", "v")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unapproved-blocked-host.com");

        assertThatThrownBy(() -> apiClient.put(unapproved, Map.of("k", "v")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unapproved-blocked-host.com");

        assertThatThrownBy(() -> apiClient.patch(unapproved, Map.of("k", "v")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unapproved-blocked-host.com");

        assertThatThrownBy(() -> apiClient.delete(unapproved))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unapproved-blocked-host.com");

        assertThat(server.getCapturedRequests()).isEmpty();
    }

    @Test(description = "Verify malformed URI syntax in endpoint is rejected before dispatch")
    @Story("Error & Edge Cases")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that passing a malformed URI syntax in endpoint throws an exception before any network call.")
    public void testMalformedUriSyntaxInEndpointRejectedBeforeDispatch() {
        server.clearCapturedRequests();

        assertThatThrownBy(() -> apiClient.get("http://invalid [host]/api"))
                .isInstanceOf(Exception.class);

        assertThat(server.getCapturedRequests()).isEmpty();
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

    @Test(description = "Verify parallel requests with distinct headers, query params, auth, and bodies do not leak cross-thread")
    @Story("Concurrency & Thread Safety")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Dispatches concurrent POST requests across 10 threads with distinct headers, bearer tokens, query params, and bodies, verifying complete thread isolation.")
    public void testParallelRequestsDoNotLeakHeadersQueryParamsAuthOrBodies() throws InterruptedException {
        int threadCount = 10;
        server.registerHandler("/api/concurrent-isolation", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            String queryWorker = "";
            if (query != null && query.contains("workerId=")) {
                queryWorker = query.substring(query.indexOf("workerId=") + "workerId=".length());
                if (queryWorker.contains("&")) {
                    queryWorker = queryWorker.substring(0, queryWorker.indexOf("&"));
                }
            }

            String headerWorker = exchange.getRequestHeaders().getFirst("X-Worker-Correlation-ID");
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");

            // Verify intra-request coherence for headers and query parameters
            boolean headerMatches = headerWorker != null && headerWorker.equals(queryWorker);
            boolean authMatches = authHeader != null && authHeader.equals("Bearer token-" + queryWorker);

            if (!headerMatches || !authMatches) {
                String error = String.format("Cross-contamination detected: query=%s, header=%s, auth=%s",
                        queryWorker, headerWorker, authHeader);
                byte[] errBytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(409, errBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errBytes);
                }
                return;
            }

            byte[] okBytes = ("{\"worker\":\"" + queryWorker + "\",\"status\":\"isolated\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, okBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(okBytes);
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<String> receivedWorkerIds = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 1; i <= threadCount; i++) {
            final String workerId = "thread-worker-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    RequestSpecification threadSpec = new RequestSpecBuilder()
                            .addHeader("X-Worker-Correlation-ID", workerId)
                            .addHeader("Authorization", "Bearer token-" + workerId)
                            .addQueryParam("workerId", workerId)
                            .build();

                    Map<String, Object> body = Map.of("worker", workerId, "index", 100);
                    Response resp = apiClient.post("/api/concurrent-isolation", body, threadSpec);

                    if (resp.getStatusCode() != 200) {
                        throw new IllegalStateException("Expected 200, but got " + resp.getStatusCode()
                                + ": " + resp.asString());
                    }
                    String returnedWorker = resp.jsonPath().getString("worker");
                    if (!workerId.equals(returnedWorker)) {
                        throw new IllegalStateException("Cross-thread contamination: expected " + workerId
                                + " but got " + returnedWorker);
                    }
                    receivedWorkerIds.add(returnedWorker);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completedInTime = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).as("All concurrent requests must complete within 15 seconds").isTrue();
        assertThat(errors).as("No threads should encounter cross-contamination or errors: %s", errors).isEmpty();
        assertThat(receivedWorkerIds).hasSize(threadCount);
        for (int i = 1; i <= threadCount; i++) {
            assertThat(receivedWorkerIds).contains("thread-worker-" + i);
        }

        List<CapturedRequest> captured = server.getRequestsForPath("/api/concurrent-isolation");
        assertThat(captured).hasSize(threadCount);

        for (CapturedRequest req : captured) {
            String queryWorker = req.getFirstQueryParam("workerId");
            String headerWorker = req.getFirstHeader("X-Worker-Correlation-ID");
            String authHeader = req.getFirstHeader("Authorization");
            String bodyText = req.getBodyText();

            assertThat(headerWorker).isEqualTo(queryWorker);
            assertThat(authHeader).isEqualTo("Bearer token-" + queryWorker);
            assertThat(bodyText).contains("\"worker\":\"" + queryWorker + "\"");
        }
    }

    @Test(description = "Verify configuration remains safely readable and consistent during parallel execution")
    @Story("Concurrency & Thread Safety")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes concurrent configuration property reads across 10 threads, confirming thread safety and zero race conditions.")
    public void testConcurrentConfigInitializationAndReadability() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterationsPerThread; j++) {
                        ConfigManager config = ConfigManager.getInstance();
                        String baseUrl = config.getBaseUrl();
                        int timeout = config.getHttpTimeoutSeconds();
                        boolean loopback = config.isHostAllowed("127.0.0.1");
                        boolean allowed = config.isHostAllowed("dummyjson.com");
                        boolean denied = config.isHostAllowed("unapproved.com");

                        if (baseUrl == null || baseUrl.isBlank()) {
                            throw new IllegalStateException("baseUrl is null or blank");
                        }
                        if (timeout <= 0) {
                            throw new IllegalStateException("timeout is not positive");
                        }
                        if (!loopback || !allowed || denied) {
                            throw new IllegalStateException("Host allowlist returned inconsistent results");
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completedInTime = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).as("All configuration read threads must complete within timeout").isTrue();
        assertThat(errors).as("No concurrent configuration read errors should occur: %s", errors).isEmpty();
    }

    // ==========================================
    // 6. URL Security & Mode Policy Enforcement
    // ==========================================

    @Test(description = "Verify offline execution blocks requests targeting external hosts even if allowlisted")
    @Story("URL Security & Mode Enforcement")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that in offline mode, ApiClient blocks attempts to dispatch requests to external hosts like DummyJSON before any socket is opened.")
    public void testOfflineExecutionBlocksExternalNetworkRequests() {
        System.setProperty("test.profile", "offline");
        ConfigManager.reset();

        assertThatThrownBy(() -> apiClient.get("https://dummyjson.com/products"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Offline mode strictly prohibits external network calls to: dummyjson.com");
    }

    @Test(description = "Verify ApiClient rejects unapproved external hosts not in allowlist")
    @Story("URL Security & Mode Enforcement")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that ApiClient strictly rejects requests targeting unapproved external hosts before execution.")
    public void testApiClientRejectsUnapprovedExternalHost() {
        System.setProperty("test.profile", "integration");
        ConfigManager.reset();

        assertThatThrownBy(() -> apiClient.get("https://unapproved-external-host.com/api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not permitted by allowlist");
    }

    @Test(description = "Verify ApiClient rejects unsafe schemes like FTP or file URLs")
    @Story("URL Security & Mode Enforcement")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that ApiClient rejects non-http/https schemes such as ftp:// or file:// before execution.")
    public void testApiClientRejectsUnsafeSchemeEndpoint() {
        assertThatThrownBy(() -> apiClient.get("ftp://127.0.0.1:21/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use http or https scheme");

        assertThatThrownBy(() -> apiClient.get("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use http or https scheme");
    }

    // ==========================================
    // 7. Stage 3 — Security Controls, Custom Spec Isolation & Redirects
    // ==========================================

    @Test(description = "Verify allowed final destination succeeds and captured on test server")
    @Story("Destination Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that combining base URL and a valid endpoint dispatches correctly to local server, returning 200.")
    public void testAllowedFinalDestinationSucceeds() {
        server.registerJsonResponse("/api/stage3/allowed", 200, "{\"destination\":\"allowed\"}");

        Response response = apiClient.get("/api/stage3/allowed");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("destination")).isEqualTo("allowed");
        assertThat(server.getLastRequest().getPath()).isEqualTo("/api/stage3/allowed");
    }

    @Test(description = "Verify validated destination executes against local test server with exact path and query")
    @Story("Destination Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Proves that requests passing destination validation execute against the local test server with exact path and query parameters.")
    public void testValidatedDestinationMatchesActualServerDestination() {
        server.clearCapturedRequests();
        server.registerJsonResponse("/api/stage3/match", 200, "{\"matched\":true}");

        Response response = apiClient.get("/api/stage3/match", Map.of("filter", "active", "limit", 10));

        assertThat(response.getStatusCode()).isEqualTo(200);

        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getMethod()).isEqualTo("GET");
        assertThat(captured.getPath()).isEqualTo("/api/stage3/match");
        assertThat(captured.getFirstQueryParam("filter")).isEqualTo("active");
        assertThat(captured.getFirstQueryParam("limit")).isEqualTo("10");
        assertThat(captured.getFirstHeader("Host")).contains(String.valueOf(server.getPort()));
    }

    @Test(description = "Verify blocked final destination throws exception and performs no network dispatch")
    @Story("Destination Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that when final destination validation fails, no network dispatch is executed.")
    public void testBlockedFinalDestinationThrowsWithoutNetworkDispatch() {
        server.clearCapturedRequests();

        assertThatThrownBy(() -> apiClient.get("https://unapproved-blocked-host.com/api/test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not permitted by allowlist");

        assertThatThrownBy(() -> apiClient.get("ftp://127.0.0.1:21/api/test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use http or https scheme");

        RequestSpecification maliciousBasePathSpec = new RequestSpecBuilder()
                .setBasePath("//evil.com")
                .build();
        assertThatThrownBy(() -> apiClient.get("/api/stage3/test", maliciousBasePathSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evil.com");

        assertThat(server.getCapturedRequests()).isEmpty();
    }

    @Test(description = "Verify custom specification cannot override protected baseUri")
    @Story("Custom Specification Isolation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that attempting to set baseUri in a custom RequestSpecification is overridden by the framework's locked base URI.")
    public void testCustomSpecCannotOverrideBaseUri() {
        server.registerJsonResponse("/api/stage3/baseuri-protection", 200, "{\"protected\":true}");
        RequestSpecification customSpec = new RequestSpecBuilder()
                .setBaseUri("https://evil.com")
                .addHeader("X-Legit-Header", "legit-val")
                .build();

        Response response = apiClient.get("/api/stage3/baseuri-protection", customSpec);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getBoolean("protected")).isTrue();
        CapturedRequest captured = server.getLastRequest();
        assertThat(captured).isNotNull();
        assertThat(captured.getPath()).isEqualTo("/api/stage3/baseuri-protection");
        assertThat(captured.getFirstHeader("X-Legit-Header")).isEqualTo("legit-val");
    }

    @Test(description = "Verify custom specification cannot override protected destination port")
    @Story("Custom Specification Isolation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that custom specifications setting an arbitrary port cannot divert traffic away from the configured port.")
    public void testCustomSpecCannotOverridePort() {
        server.registerJsonResponse("/api/stage3/port-protection", 200, "{\"portProtected\":true}");
        RequestSpecification customSpec = new RequestSpecBuilder()
                .setPort(65432)
                .build();

        Response response = apiClient.get("/api/stage3/port-protection", customSpec);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getBoolean("portProtected")).isTrue();
        assertThat(server.getLastRequest().getPath()).isEqualTo("/api/stage3/port-protection");
    }

    @Test(description = "Verify custom specification injecting malicious basePath is rejected without network dispatch")
    @Story("Custom Specification Isolation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that a custom specification attempting protocol-relative or scheme injection via basePath is rejected before dispatch.")
    public void testCustomSpecInjectingMaliciousBasePathRejectedWithoutDispatch() {
        server.clearCapturedRequests();
        RequestSpecification customSpec = new RequestSpecBuilder()
                .setBasePath("//evil.com")
                .build();

        assertThatThrownBy(() -> apiClient.get("/api/stage3/test", customSpec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evil.com");

        assertThat(server.getCapturedRequests()).isEmpty();
    }

    @Test(description = "Verify automatic redirects are disabled and do not silently follow to unapproved external hosts")
    @Story("Redirect Security")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that a 302 response with an external Location header is returned without following the redirect.")
    public void testAutomaticRedirectsAreDisabledAndDoNotSilentlyBypassAllowlist() {
        server.registerHandler("/api/stage3/redirect-external", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://evil.com/leak-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        Response response = apiClient.get("/api/stage3/redirect-external");

        assertThat(response.getStatusCode()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("https://evil.com/leak-data");
    }

    @Test(description = "Verify custom specification cannot enable automatic redirect following")
    @Story("Redirect Security")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that custom specifications passing followRedirects(true) have redirect restrictions re-enforced at execution layer.")
    public void testCustomSpecCannotEnableAutomaticRedirects() {
        server.registerHandler("/api/stage3/redirect-override", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://evil.com/override-leak");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        RequestSpecification customSpec = new RequestSpecBuilder()
                .setConfig(RestAssuredConfig.config().redirect(RedirectConfig.redirectConfig().followRedirects(true)))
                .build();

        Response response = apiClient.get("/api/stage3/redirect-override", customSpec);

        assertThat(response.getStatusCode()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("https://evil.com/override-leak");
    }

    @Test(description = "Verify connection and read timeouts are explicitly configured on RequestSpecification")
    @Story("Timeout Configuration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that default RequestSpecification has explicit connection, socket, and connection-manager timeouts configured.")
    public void testTimeoutsExplicitlyConfiguredOnSpecification() {
        RequestSpecification spec = RequestSpecFactory.getDefaultRequestSpec();
        FilterableRequestSpecification filterable = (FilterableRequestSpecification) spec;

        RestAssuredConfig config = filterable.getConfig();
        assertThat(config).isNotNull();
        assertThat(config.getRedirectConfig().followsRedirects()).isFalse();
        assertThat(config.getHttpClientConfig()).isNotNull();

        int expectedTimeoutMillis = ConfigManager.getInstance().getHttpTimeoutSeconds() * 1000;
        Map<String, ?> params = config.getHttpClientConfig().params();
        assertThat(params.get("http.connection.timeout")).isEqualTo(expectedTimeoutMillis);
        assertThat(params.get("http.socket.timeout")).isEqualTo(expectedTimeoutMillis);
        assertThat(params.get("http.connection-manager.timeout")).isEqualTo((long) expectedTimeoutMillis);
    }

    @Test(description = "Verify custom specification cannot remove or disable configured socket timeout")
    @Story("Timeout Configuration")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that a custom specification attempting to set zero/infinite socket timeout has configured timeouts re-enforced.")
    public void testCustomSpecCannotRemoveConfiguredTimeouts() {
        server.registerJsonResponse("/api/stage3/timeout-override", 200, "{\"delayed\":true}", Duration.ofMillis(2500));
        System.setProperty("http.timeout.seconds", "1");
        ConfigManager.reset();

        RequestSpecification customSpec = new RequestSpecBuilder()
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.socket.timeout", 0)))
                .build();

        assertThatThrownBy(() -> apiClient.get("/api/stage3/timeout-override", customSpec))
                .satisfies(throwable -> {
                    boolean isTimeout = throwable instanceof SocketTimeoutException
                            || (throwable.getCause() instanceof SocketTimeoutException);
                    assertThat(isTimeout)
                            .as("Expected SocketTimeoutException due to enforced 1-second timeout, got %s", throwable.getClass().getName())
                            .isTrue();
                });
    }
}
