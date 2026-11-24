package com.sksharma.api.tests.clients;

import com.sksharma.api.clients.ApiClient;
import com.sksharma.api.clients.RequestSpecFactory;
import com.sksharma.api.clients.ResponseSpecFactory;
import com.sksharma.api.config.ConfigManager;
import com.sksharma.api.constants.EndpointConstants;
import com.sksharma.api.constants.HeaderConstants;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Epic("Core Framework")
@Feature("HTTP Client")
public class ApiClientTest {

    private ApiClient apiClient;

    private static final String IDENTIFIABLE_QUERY_SECRET = "secret_live_query_token_9999_test";
    private static final String IDENTIFIABLE_BEARER_SECRET = "secret_live_bearer_token_7777_test";
    private static final String IDENTIFIABLE_PASSWORD_SECRET = "secret_live_password_8888_test";

    @BeforeClass
    public void setup() {
        apiClient = new ApiClient();
    }

    @BeforeMethod
    @AfterMethod
    public void cleanupProperties() {
        System.clearProperty("base.url");
        System.clearProperty("http.timeout.seconds");
        System.clearProperty("logging.include.body");
        ConfigManager.reset();
    }

    @Test(description = "Verify GET execution using ApiClient against DummyJSON diagnostics endpoint")
    @Story("HTTP Execution")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Executes an HTTP GET request against DummyJSON diagnostics endpoint and verifies response code and JSON body.")
    public void testGetMethod() {
        Response response = apiClient.get(EndpointConstants.TEST);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.jsonPath().getString("status")).isEqualTo("ok");
        assertThat(response.jsonPath().getString("method")).isEqualTo("GET");

        response.then().spec(ResponseSpecFactory.getSuccessResponseSpec());
    }

    @Test(description = "Verify POST execution using ApiClient creating a resource")
    @Story("HTTP Execution")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes an HTTP POST request to create a simulated resource and validates 201 status code.")
    public void testPostMethod() {
        Map<String, Object> payload = Map.of(
                "title", "Framework Test Item",
                "price", 49.99,
                "category", "electronics"
        );

        Response response = apiClient.post(EndpointConstants.PRODUCTS_ADD, payload);

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.jsonPath().getString("title")).isEqualTo("Framework Test Item");
        assertThat(response.jsonPath().getInt("id")).isGreaterThan(0);

        response.then().spec(ResponseSpecFactory.getJsonResponseSpec());
    }

    @Test(description = "Verify PUT execution using ApiClient updating an entire resource")
    @Story("HTTP Execution")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes an HTTP PUT request replacing a resource and validates 200 response with updated data.")
    public void testPutMethod() {
        Map<String, Object> payload = Map.of(
                "title", "Updated Test Item Via PUT",
                "price", 39.99
        );

        Response response = apiClient.put("/products/1", payload);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("title")).isEqualTo("Updated Test Item Via PUT");
        assertThat(response.jsonPath().getDouble("price")).isEqualTo(39.99);
    }

    @Test(description = "Verify PATCH execution using ApiClient updating a specific field")
    @Story("HTTP Execution")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes an HTTP PATCH request updating specific resource fields and validates 200 response.")
    public void testPatchMethod() {
        Map<String, Object> payload = Map.of(
                "title", "Patched Test Item Title"
        );

        Response response = apiClient.patch("/products/1", payload);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("title")).isEqualTo("Patched Test Item Title");
    }

    @Test(description = "Verify DELETE execution using ApiClient simulating resource deletion")
    @Story("HTTP Execution")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes an HTTP DELETE request simulating deletion and validates response flags.")
    public void testDeleteMethod() {
        Response response = apiClient.delete("/products/1");

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getBoolean("isDeleted")).isTrue();
        assertThat(response.jsonPath().getString("deletedOn")).isNotNull();
    }

    @Test(description = "Verify query parameters, auth headers, and body secrets are redacted across tested logs and Allure")
    @Story("Sensitive Data Protection")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Performs a live request with sensitive query parameters, headers, and body secrets and verifies synthetic secret redaction across tested logging and reporting paths.")
    public void testSensitiveDataRedactionInLogsAndAllure() throws IOException {
        // Enable body logging for this test to verify body redaction
        System.setProperty("logging.include.body", "true");
        ConfigManager.reset();

        Map<String, Object> queryParams = Map.of(
                "apiKey", IDENTIFIABLE_QUERY_SECRET,
                "version", "1.0"
        );

        Map<String, Object> payload = Map.of(
                "username", "automation.test.user",
                "password", IDENTIFIABLE_PASSWORD_SECRET
        );

        RequestSpecification authSpec = RequestSpecFactory.getAuthenticatedRequestSpec(IDENTIFIABLE_BEARER_SECRET)
                .queryParams(queryParams);

        // 0. Ensure the request specification actually contains the synthetic query secret before transmission
        io.restassured.specification.FilterableRequestSpecification filterableSpec =
                (io.restassured.specification.FilterableRequestSpecification) authSpec;
        assertThat(filterableSpec.getQueryParams())
                .as("Request specification must actually contain the query parameter secret before transmission")
                .containsKey("apiKey")
                .containsEntry("apiKey", IDENTIFIABLE_QUERY_SECRET);

        assertThat(filterableSpec.getHeaders().getValue("Authorization"))
                .as("Request specification must actually contain the Bearer token secret before transmission")
                .contains(IDENTIFIABLE_BEARER_SECRET);

        Response response = apiClient.post(EndpointConstants.AUTH_LOGIN, payload, authSpec);
        assertThat(response.getStatusCode()).isGreaterThanOrEqualTo(400);

        // 1. Verify Application Log Output
        Path logPath = Paths.get("logs/api-automation.log");
        assertThat(Files.exists(logPath))
                .as("Application log file must exist at %s", logPath)
                .isTrue();

        String logContent = Files.readString(logPath);
        assertThat(logContent)
                .as("Log content must not be blank")
                .isNotBlank();

        assertThat(logContent)
                .as("Application log must not contain query parameter secrets")
                .doesNotContain(IDENTIFIABLE_QUERY_SECRET);

        assertThat(logContent)
                .as("Application log must not contain Bearer token secrets")
                .doesNotContain(IDENTIFIABLE_BEARER_SECRET);

        assertThat(logContent)
                .as("Application log must not contain raw password secrets")
                .doesNotContain(IDENTIFIABLE_PASSWORD_SECRET);

        // 2. Verify Allure Results Attachments
        Path allureResultsDir = Paths.get("target/allure-results");
        assertThat(Files.exists(allureResultsDir))
                .as("Allure results directory must exist at %s", allureResultsDir)
                .isTrue();

        List<Path> attachmentFiles;
        try (Stream<Path> files = Files.walk(allureResultsDir)) {
            attachmentFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("-attachment.txt") || p.toString().endsWith(".json"))
                    .toList();
        }

        assertThat(attachmentFiles)
                .as("Allure must generate report attachments during execution")
                .isNotEmpty();

        for (Path attachmentFile : attachmentFiles) {
            String content = Files.readString(attachmentFile);
            assertThat(content)
                    .as("Allure file %s must not contain query parameter secret", attachmentFile.getFileName())
                    .doesNotContain(IDENTIFIABLE_QUERY_SECRET);

            assertThat(content)
                    .as("Allure file %s must not contain Bearer secret", attachmentFile.getFileName())
                    .doesNotContain(IDENTIFIABLE_BEARER_SECRET);

            assertThat(content)
                    .as("Allure file %s must not contain password secret", attachmentFile.getFileName())
                    .doesNotContain(IDENTIFIABLE_PASSWORD_SECRET);
        }
    }

    @Test(description = "Verify thread-safety and lack of cross-thread contamination during concurrent executions")
    @Story("Concurrency & Thread Safety")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Simultaneously executes multiple threads through ApiClient to confirm thread safety and lack of cross-thread contamination.")
    public void testConcurrentExecutionThreadSafety() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Response resp = apiClient.get(EndpointConstants.TEST);
                    statusCodes.add(resp.getStatusCode());
                } catch (Exception e) {
                    errors.add(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads simultaneously
        boolean completedInTime = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).as("All concurrent requests should complete within timeout").isTrue();
        assertThat(errors).as("No threads should encounter errors during concurrent execution").isEmpty();
        assertThat(statusCodes).hasSize(threadCount);
        assertThat(statusCodes).allMatch(code -> code == 200);
    }

    @Test(description = "Verify runtime configuration override directs HTTP requests to the configured destination URL")
    @Story("Request Specification")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Spins up a local HTTP test server, overrides base.url, and validates that ApiClient sends requests to the overridden destination URL.")
    public void testRuntimeConfigOverride() throws IOException {
        com.sun.net.httpserver.HttpServer localServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        java.util.concurrent.atomic.AtomicBoolean requestReceived = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<String> requestedUri = new java.util.concurrent.atomic.AtomicReference<>();

        localServer.createContext("/api/override-test", exchange -> {
            requestReceived.set(true);
            requestedUri.set(exchange.getRequestURI().toString());
            byte[] responseBytes = "{\"status\":\"override-ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        localServer.start();

        int port = localServer.getAddress().getPort();
        String overrideUrl = "http://localhost:" + port;

        try {
            System.setProperty("base.url", overrideUrl);
            ConfigManager.reset();

            // 1. Verify RequestSpecFactory reflects the override base URI
            RequestSpecification spec = RequestSpecFactory.getDefaultRequestSpec();
            io.restassured.specification.FilterableRequestSpecification filterableSpec =
                    (io.restassured.specification.FilterableRequestSpecification) spec;
            assertThat(filterableSpec.getBaseUri()).isEqualTo(overrideUrl);

            // 2. Execute actual request via ApiClient to verify network dispatch to overridden URL
            Response response = apiClient.get("/api/override-test");
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.jsonPath().getString("status")).isEqualTo("override-ok");

            // 3. Confirm the local server actually received the request
            assertThat(requestReceived.get())
                    .as("Local server must have received request via overridden base URL")
                    .isTrue();
            assertThat(requestedUri.get()).isEqualTo("/api/override-test");
        } finally {
            localServer.stop(0);
            System.clearProperty("base.url");
            ConfigManager.reset();
        }
    }
}
