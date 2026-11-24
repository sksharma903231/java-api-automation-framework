package com.sksharma.api.tests.listeners;

import io.qameta.allure.Allure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.Arrays;

/**
 * TestNG listener that records test execution lifecycle events, logs outcomes,
 * and attaches failure diagnostics to Allure without exposing sensitive data.
 */
public class TestExecutionListener implements ITestListener {

    private static final Logger LOG = LoggerFactory.getLogger(TestExecutionListener.class);

    @Override
    public void onStart(ITestContext context) {
        String[] includedGroups = context.getIncludedGroups();
        if (includedGroups != null && Arrays.asList(includedGroups).contains("integration")) {
            LOG.info("================================================================================");
            LOG.info("[INTEGRATION TEST PROFILE ACTIVE]");
            LOG.info("Executing integration test suite against DummyJSON API (base URL: {}).",
                    com.sksharma.api.config.ConfigManager.getInstance().getBaseUrl());
            LOG.info("Active internet connectivity and DummyJSON service availability are required.");
            LOG.info("================================================================================");
        } else if (includedGroups != null && Arrays.asList(includedGroups).contains("offline")) {
            LOG.info("================================================================================");
            LOG.info("[OFFLINE TEST PROFILE ACTIVE]");
            LOG.info("Executing deterministic local test suite without external network dependencies.");
            LOG.info("================================================================================");
        } else {
            LOG.info("================================================================================");
            LOG.info("[FULL TEST SUITE ACTIVE]");
            LOG.info("Executing complete test suite (both offline and external integration tests).");
            LOG.info("================================================================================");
        }
    }

    @Override
    public void onTestStart(ITestResult result) {
        LOG.info("=== STARTING TEST: {}.{}() - '{}' ===",
                result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(),
                result.getMethod().getDescription());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        long duration = result.getEndMillis() - result.getStartMillis();
        LOG.info("=== PASSED TEST: {}.{}() [{} ms] ===",
                result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(),
                duration);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        long duration = result.getEndMillis() - result.getStartMillis();
        Throwable throwable = result.getThrowable();
        String rawMessage = throwable != null ? throwable.getMessage() : "No exception message provided";
        String sanitizedMessage = com.sksharma.api.filters.SensitiveDataRedactionFilter.sanitizeText(rawMessage);

        LOG.error("=== FAILED TEST: {}.{}() [{} ms] - Error: {} ===",
                result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(),
                duration,
                sanitizedMessage);

        // Attach sanitized failure diagnostics to Allure
        String diagnostics = buildFailureDiagnostics(result);
        try {
            Allure.addAttachment("Failure Diagnostics - " + result.getMethod().getMethodName(), "text/plain", diagnostics);
        } catch (Exception e) {
            LOG.warn("Failed to attach failure diagnostics to Allure due to a reporting error");
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        Throwable throwable = result.getThrowable();
        String rawSkipReason = throwable != null ? throwable.getMessage() : "Unknown skip reason";
        String sanitizedSkipReason = com.sksharma.api.filters.SensitiveDataRedactionFilter.sanitizeText(rawSkipReason);
        LOG.warn("=== SKIPPED TEST: {}.{}() - Reason: {} ===",
                result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(),
                sanitizedSkipReason);
    }

    private String buildFailureDiagnostics(ITestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("Test Class: ").append(result.getTestClass().getName()).append("\n");
        sb.append("Test Method: ").append(result.getMethod().getMethodName()).append("\n");
        if (result.getMethod().getDescription() != null) {
            sb.append("Description: ").append(result.getMethod().getDescription()).append("\n");
        }

        Throwable throwable = result.getThrowable();
        if (throwable != null) {
            sb.append("Failure Type: ").append(throwable.getClass().getName()).append("\n");
            String rawMessage = throwable.getMessage();
            sb.append("Sanitized Message: ").append(com.sksharma.api.filters.SensitiveDataRedactionFilter.sanitizeText(rawMessage)).append("\n");

            StackTraceElement[] stack = throwable.getStackTrace();
            if (stack != null && stack.length > 0) {
                sb.append("Failure Origin: ").append(stack[0]).append("\n");
                for (StackTraceElement elem : stack) {
                    if (elem.getClassName().startsWith("com.sksharma.api")) {
                        sb.append("Framework Location: ").append(elem).append("\n");
                        break;
                    }
                }
            }
        }
        return sb.toString();
    }
}
