package com.sksharma.api.tests.listeners;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.testng.IClass;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security & Observability")
@Feature("Listener Security")
public class TestExecutionListenerSecurityTest {

    private static final String SYNTHETIC_EXCEPTION_SECRET = "exception_secret_password_777_test";
    private static final String SYNTHETIC_TOKEN_SECRET = "exception_token_bearer_888_test";
    private static final String SYNTHETIC_QUERY_SECRET = "exception_query_apikey_999_test";

    @Test(description = "Verify synthetic secrets in exception messages are redacted from logs and Allure attachments")
    @Story("Exception Sanitization")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Simulates test failure with sensitive tokens/passwords in exception messages and verifies synthetic secret redaction across tested logging and reporting paths.")
    public void testExceptionMessageSecretsAreRedacted() throws IOException {
        TestExecutionListener listener = new TestExecutionListener();

        String rawSecretMessage = String.format(
                "Connection failed: user=admin, password=%s, url=https://dummyjson.com/auth?apiKey=%s, header=Bearer %s",
                SYNTHETIC_EXCEPTION_SECRET,
                SYNTHETIC_QUERY_SECRET,
                SYNTHETIC_TOKEN_SECRET
        );

        Throwable syntheticException = new RuntimeException(rawSecretMessage);
        syntheticException.setStackTrace(Thread.currentThread().getStackTrace());

        ITestResult stubResult = createStubTestResult("com.sksharma.api.tests.SampleTest", "sampleTestMethod", syntheticException);

        // 1. Snapshot existing Allure attachment files and log state before invoking listener failure hook
        Path allureResultsDir = Paths.get("target/allure-results");
        Files.createDirectories(allureResultsDir);
        java.util.Set<Path> existingAttachments;
        try (Stream<Path> files = Files.walk(allureResultsDir)) {
            existingAttachments = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("-attachment.txt"))
                    .collect(java.util.stream.Collectors.toSet());
        }

        Path logPath = Paths.get("logs/api-automation.log");
        long initialLogSize = Files.exists(logPath) ? Files.size(logPath) : 0L;

        // 2. Execute listener failure hook
        listener.onTestFailure(stubResult);

        // 3. Verify newly created Allure attachment exists and is verified
        List<Path> newAttachments;
        try (Stream<Path> files = Files.walk(allureResultsDir)) {
            newAttachments = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith("-attachment.txt"))
                    .filter(p -> !existingAttachments.contains(p))
                    .toList();
        }

        assertThat(newAttachments)
                .as("The failure hook must actively generate a new Allure failure diagnostic attachment")
                .isNotEmpty();

        for (Path attachmentFile : newAttachments) {
            String content = Files.readString(attachmentFile);
            assertThat(content)
                    .as("Allure attachment %s must contain diagnostic failure structure", attachmentFile.getFileName())
                    .contains("Test Class: com.sksharma.api.tests.SampleTest")
                    .contains("Test Method: sampleTestMethod")
                    .contains("Failure Type: java.lang.RuntimeException")
                    .contains("Sanitized Message:")
                    .as("Allure attachment %s must not contain synthetic secrets", attachmentFile.getFileName())
                    .doesNotContain(SYNTHETIC_EXCEPTION_SECRET)
                    .doesNotContain(SYNTHETIC_QUERY_SECRET)
                    .doesNotContain(SYNTHETIC_TOKEN_SECRET);
        }

        // 4. Verify Application Log appends new failure event and does not leak synthetic secrets
        assertThat(Files.exists(logPath))
                .as("Log file must exist at %s", logPath)
                .isTrue();

        long newLogSize = Files.size(logPath);
        assertThat(newLogSize)
                .as("Log file must grow after invoking listener.onTestFailure")
                .isGreaterThan(initialLogSize);

        byte[] allLogBytes = Files.readAllBytes(logPath);
        byte[] appendedBytes = java.util.Arrays.copyOfRange(allLogBytes, (int) initialLogSize, allLogBytes.length);
        String newlyAppendedLog = new String(appendedBytes, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(newlyAppendedLog)
                .as("Newly appended log content must record the failure event for sampleTestMethod")
                .contains("=== FAILED TEST: ")
                .contains("sampleTestMethod()")
                .as("Newly appended log content must not leak the synthetic password secret")
                .doesNotContain(SYNTHETIC_EXCEPTION_SECRET)
                .as("Newly appended log content must not leak the synthetic query secret")
                .doesNotContain(SYNTHETIC_QUERY_SECRET)
                .as("Newly appended log content must not leak the synthetic token secret")
                .doesNotContain(SYNTHETIC_TOKEN_SECRET);
    }

    private ITestResult createStubTestResult(String className, String methodName, Throwable throwable) {
        ITestNGMethod testMethod = (ITestNGMethod) Proxy.newProxyInstance(
                ITestNGMethod.class.getClassLoader(),
                new Class<?>[]{ITestNGMethod.class},
                (proxy, method, args) -> {
                    if ("getMethodName".equals(method.getName())) {
                        return methodName;
                    }
                    if ("getDescription".equals(method.getName())) {
                        return "Synthetic failure diagnostic test";
                    }
                    return null;
                }
        );

        IClass testClass = (IClass) Proxy.newProxyInstance(
                IClass.class.getClassLoader(),
                new Class<?>[]{IClass.class},
                (proxy, method, args) -> {
                    if ("getName".equals(method.getName())) {
                        return className;
                    }
                    if ("getRealClass".equals(method.getName())) {
                        return TestExecutionListenerSecurityTest.class;
                    }
                    return null;
                }
        );

        return (ITestResult) Proxy.newProxyInstance(
                ITestResult.class.getClassLoader(),
                new Class<?>[]{ITestResult.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getMethod" -> testMethod;
                        case "getTestClass" -> testClass;
                        case "getThrowable" -> throwable;
                        case "getStartMillis" -> 1000L;
                        case "getEndMillis" -> 1050L;
                        case "getStatus" -> ITestResult.FAILURE;
                        default -> null;
                    };
                }
        );
    }
}
