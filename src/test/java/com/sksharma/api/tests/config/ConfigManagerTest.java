package com.sksharma.api.tests.config;

import com.sksharma.api.config.ConfigManager;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Core Framework")
@Feature("Configuration Management")
public class ConfigManagerTest {

    @BeforeMethod
    @AfterMethod
    public void cleanupProperties() {
        System.clearProperty("base.url");
        System.clearProperty("http.timeout.seconds");
        System.clearProperty("logging.include.body");
        System.clearProperty("custom.test.key");
        ConfigManager.reset();
    }

    @Test(description = "Verify default configuration values are properly loaded from config.properties")
    @Story("Configuration Loading")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Loads default configuration from config.properties and validates defaults for base URL, timeout, and logging.")
    public void testDefaultConfigLoaded() {
        ConfigManager config = ConfigManager.getInstance();

        assertThat(config.getBaseUrl())
                .isNotNull()
                .isEqualTo("https://dummyjson.com");

        assertThat(config.getHttpTimeoutSeconds())
                .isEqualTo(10);

        assertThat(config.isBodyLoggingEnabled())
                .isFalse();
    }

    @Test(description = "Verify Java system properties take precedence over properties file")
    @Story("Runtime Overrides")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that Java system properties override configuration loaded from the properties file.")
    public void testSystemPropertyOverrideTakesPrecedence() {
        String overriddenUrl = "https://staging.dummyjson.com";
        System.setProperty("base.url", overriddenUrl);
        ConfigManager.reset();

        ConfigManager config = ConfigManager.getInstance();
        assertThat(config.getBaseUrl()).isEqualTo(overriddenUrl);
    }

    @Test(description = "Verify fallback to default value when a non-existent key is queried")
    @Story("Configuration Defaults")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies fallback mechanism when querying non-existent configuration keys.")
    public void testFallbackToDefaultValue() {
        ConfigManager config = ConfigManager.getInstance();
        String result = config.getProperty("non.existent.key", "default-fallback");

        assertThat(result).isEqualTo("default-fallback");
    }

    @Test(description = "Verify int property parsing and default handling for non-numeric input")
    @Story("Configuration Parsing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies graceful fallback to default value when integer property contains non-numeric value.")
    public void testIntPropertyParsingAndFallback() {
        System.setProperty("http.timeout.seconds", "not-a-number");
        ConfigManager.reset();

        ConfigManager config = ConfigManager.getInstance();
        assertThat(config.getHttpTimeoutSeconds()).isEqualTo(10);
    }

    @Test(description = "Verify boolean property parsing")
    @Story("Configuration Parsing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies parsing of boolean configuration flags.")
    public void testBooleanPropertyParsing() {
        System.setProperty("logging.include.body", "true");
        ConfigManager.reset();

        ConfigManager config = ConfigManager.getInstance();
        assertThat(config.isBodyLoggingEnabled()).isTrue();
    }

    @Test(description = "Verify configuration validation rejects non-HTTP/HTTPS schemes")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that non-HTTP/HTTPS URLs are strictly rejected during initialization.")
    public void testValidationRejectionOnInvalidUrlScheme() {
        System.setProperty("base.url", "ftp://dummyjson.com");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use http or https scheme");
    }

    @Test(description = "Verify configuration validation rejects relative URIs")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that relative URIs without host/scheme are rejected.")
    public void testValidationRejectionOnRelativeUri() {
        System.setProperty("base.url", "/api/v1");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be an absolute URI");
    }

    @Test(description = "Verify configuration validation rejects URLs with embedded user credentials")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that embedded credentials in URLs (e.g. user:pass@host) are rejected for security.")
    public void testValidationRejectionOnEmbeddedUserInfo() {
        System.setProperty("base.url", "https://admin:secret@dummyjson.com");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not contain embedded user credentials");
    }

    @Test(description = "Verify configuration validation rejects base URLs with query parameters")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that query parameters in the base URL are rejected.")
    public void testValidationRejectionOnQueryParameters() {
        System.setProperty("base.url", "https://dummyjson.com?token=leak");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not contain query parameters");
    }

    @Test(description = "Verify configuration validation rejects base URLs with URI fragments")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that URI fragments in the base URL are rejected.")
    public void testValidationRejectionOnUriFragment() {
        System.setProperty("base.url", "https://dummyjson.com#section");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not contain URI fragments");
    }

    @Test(description = "Verify timeout below minimum (1 second) is rejected")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates timeout boundary lower bound check (< 1 second).")
    public void testValidationRejectionOnTimeoutBelowMinimum() {
        System.setProperty("http.timeout.seconds", "0");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 300 seconds");
    }

    @Test(description = "Verify timeout above maximum (300 seconds) is rejected")
    @Story("Configuration Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates timeout boundary upper bound check (> 300 seconds).")
    public void testValidationRejectionOnTimeoutAboveMaximum() {
        System.setProperty("http.timeout.seconds", "301");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be between 1 and 300 seconds");
    }
}
