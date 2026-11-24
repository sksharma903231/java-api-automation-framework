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
@Test(groups = "offline")
public class ConfigManagerTest {

    @BeforeMethod(alwaysRun = true)
    @AfterMethod(alwaysRun = true)
    public void cleanupProperties() {
        System.clearProperty("base.url");
        System.clearProperty("allowed.hosts");
        System.clearProperty("api.mode");
        System.clearProperty("http.timeout.seconds");
        System.clearProperty("logging.include.body");
        System.clearProperty("custom.test.key");
        ConfigManager.reset();
    }

    @Test(description = "Verify default configuration values are properly loaded from config.properties")
    @Story("Configuration Loading")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Loads default configuration from config.properties and validates defaults for base URL, allowed hosts, timeout, and logging.")
    public void testDefaultConfigLoaded() {
        ConfigManager config = ConfigManager.getInstance();

        assertThat(config.getBaseUrl())
                .isNotNull()
                .isEqualTo("https://dummyjson.com");

        assertThat(config.getAllowedHosts())
                .contains("dummyjson.com", "staging.dummyjson.com");

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

    @Test(description = "Verify non-numeric integer configuration strictly throws IllegalStateException without silent fallback")
    @Story("Configuration Parsing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that an explicitly configured non-numeric integer value fails fast rather than silently falling back to defaults.")
    public void testIntPropertyParsingRejectsNonNumericInput() {
        System.setProperty("http.timeout.seconds", "not-a-number");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid integer format for configuration property 'http.timeout.seconds'");
    }

    @Test(description = "Verify absent integer configuration cleanly returns caller-provided default value")
    @Story("Configuration Defaults")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that querying an absent integer key returns the specified default value without throwing exceptions.")
    public void testIntPropertyAbsentKeyReturnsDefault() {
        ConfigManager config = ConfigManager.getInstance();
        int timeout = config.getIntProperty("custom.absent.timeout", 25);
        assertThat(timeout).isEqualTo(25);
    }

    @Test(description = "Verify boolean property parsing for true and false values")
    @Story("Configuration Parsing")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies accurate parsing of boolean configuration flags.")
    public void testBooleanPropertyParsing() {
        System.setProperty("logging.include.body", "true");
        ConfigManager.reset();
        assertThat(ConfigManager.getInstance().isBodyLoggingEnabled()).isTrue();

        System.setProperty("logging.include.body", "false");
        ConfigManager.reset();
        assertThat(ConfigManager.getInstance().isBodyLoggingEnabled()).isFalse();
    }

    @Test(description = "Verify invalid boolean string strictly throws IllegalStateException without silent fallback")
    @Story("Configuration Parsing")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that malformed boolean configuration values fail fast rather than silently resolving to false.")
    public void testBooleanPropertyRejectsInvalidValue() {
        System.setProperty("logging.include.body", "maybe");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid boolean format for configuration property 'logging.include.body'");
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

    @Test(description = "Verify unapproved external host in base.url is strictly rejected by allowlist")
    @Story("Host Allowlisting")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that setting base.url to an external host not present in allowed.hosts throws IllegalStateException.")
    public void testUnapprovedExternalHostRejectedByAllowlist() {
        System.setProperty("base.url", "https://evil.com");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Host 'evil.com' in 'base.url' is not permitted by allowlist");
    }

    @Test(description = "Verify exact host allowlisting prevents prefix, suffix, and subdomain bypasses")
    @Story("Host Allowlisting")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that exact host matching prevents attacker attempts using evil-dummyjson.com or dummyjson.com.attacker.com.")
    public void testExactHostAllowlistingPreventsSubdomainAndPrefixBypass() {
        System.setProperty("base.url", "https://evil-dummyjson.com");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Host 'evil-dummyjson.com' in 'base.url' is not permitted by allowlist");

        System.setProperty("base.url", "https://dummyjson.com.attacker.com");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Host 'dummyjson.com.attacker.com' in 'base.url' is not permitted by allowlist");
    }

    @Test(description = "Verify allowed.hosts configuration can be overridden and extended without hardcoding")
    @Story("Host Allowlisting")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that allowed.hosts can be dynamically overridden via system properties to support custom external hosts.")
    public void testConfigurableAllowedHostsOverride() {
        System.setProperty("allowed.hosts", "custom-api.org, internal-service.net");
        System.setProperty("base.url", "https://custom-api.org");
        ConfigManager.reset();

        ConfigManager config = ConfigManager.getInstance();
        assertThat(config.getBaseUrl()).isEqualTo("https://custom-api.org");
        assertThat(config.getAllowedHosts()).contains("custom-api.org", "internal-service.net");
        assertThat(config.isHostAllowed("custom-api.org")).isTrue();
        assertThat(config.isHostAllowed("internal-service.net")).isTrue();
        assertThat(config.isHostAllowed("dummyjson.com")).isFalse();
    }

    @Test(description = "Verify loopback hosts (localhost, 127.0.0.1, 127.x.x.x, ::1) are always permitted for base.url")
    @Story("Host Allowlisting")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that standard loopback addresses (localhost, IPv4 127.0.0.0/8, IPv6 ::1) are permitted for offline execution.")
    public void testLoopbackHostsAlwaysPermittedForBaseUrl() {
        String[] loopbackUrls = {
                "http://localhost:8080",
                "http://127.0.0.1:8080",
                "http://127.0.1.1:8080",
                "http://[::1]:8080"
        };

        for (String loopbackUrl : loopbackUrls) {
            System.setProperty("base.url", loopbackUrl);
            ConfigManager.reset();

            ConfigManager config = ConfigManager.getInstance();
            assertThat(config.getBaseUrl()).isEqualTo(loopbackUrl);
        }
    }

    @Test(description = "Verify explicit offline mode strictly prohibits external base URLs")
    @Story("Mode Separation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that setting api.mode=offline strictly prohibits configuring an external base URL.")
    public void testExplicitOfflineModeProhibitsExternalBaseUrl() {
        System.setProperty("api.mode", "offline");
        System.setProperty("base.url", "https://dummyjson.com");
        ConfigManager.reset();

        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must target a loopback destination in offline mode");
    }

    @Test(description = "Verify validateRequestUrl rejects unsafe schemes, missing hosts, user credentials, and fragments")
    @Story("URL Security")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Validates that destination URLs with non-http/https schemes, credentials, or fragments are rejected prior to request dispatch.")
    public void testValidateRequestUrlRejectsUnsafeSchemesAndMalformedUrls() {
        ConfigManager config = ConfigManager.getInstance();

        // Unsafe URL schemes
        assertThatThrownBy(() -> config.validateRequestUrl("ftp://localhost:21/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use http or https scheme");

        assertThatThrownBy(() -> config.validateRequestUrl("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use http or https scheme");

        assertThatThrownBy(() -> config.validateRequestUrl("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must use http or https scheme");

        // Missing host
        assertThatThrownBy(() -> config.validateRequestUrl("http:///path"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contain a valid host");

        // Embedded credentials
        assertThatThrownBy(() -> config.validateRequestUrl("http://admin:pass@localhost:8080/api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain embedded user credentials");

        // Fragments
        assertThatThrownBy(() -> config.validateRequestUrl("http://localhost:8080/api#ref"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain URI fragments");
    }

    @Test(description = "Verify resolveRequestUrl resolves relative, absolute, and protocol-relative endpoints accurately")
    @Story("URL Security")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates proper resolution of endpoints with and without leading slashes and absolute URLs.")
    public void testResolveRequestUrlHandlesRelativeAndAbsoluteEndpoints() {
        ConfigManager config = ConfigManager.getInstance();

        assertThat(config.resolveRequestUrl("https://dummyjson.com", "/products/1"))
                .isEqualTo("https://dummyjson.com/products/1");

        assertThat(config.resolveRequestUrl("https://dummyjson.com/", "products/1"))
                .isEqualTo("https://dummyjson.com/products/1");

        assertThat(config.resolveRequestUrl("https://dummyjson.com", "https://staging.dummyjson.com/products"))
                .isEqualTo("https://staging.dummyjson.com/products");

        assertThat(config.resolveRequestUrl("https://dummyjson.com", "//dummyjson.com/users"))
                .isEqualTo("https://dummyjson.com/users");
    }

    @Test(description = "Verify valid execution modes for api.mode and test.profile")
    @Story("Mode Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that offline and integration are accepted for api.mode and test.profile.")
    public void testExecutionModeValidOfflineAndIntegrationValues() {
        System.setProperty("base.url", "http://127.0.0.1:8080");
        System.setProperty("api.mode", "offline");
        ConfigManager.reset();
        ConfigManager config = ConfigManager.getInstance();
        assertThat(config.getTestProfile()).isEqualTo("offline");
        assertThat(config.isOfflineMode()).isTrue();
        assertThat(config.isIntegrationMode()).isFalse();

        System.setProperty("api.mode", "integration");
        ConfigManager.reset();
        config = ConfigManager.getInstance();
        assertThat(config.getTestProfile()).isEqualTo("integration");
        assertThat(config.isIntegrationMode()).isTrue();
        assertThat(config.isOfflineMode()).isFalse();
    }

    @Test(description = "Verify api.mode takes precedence over test.profile")
    @Story("Mode Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verifies that api.mode overrides test.profile regardless of configuration order.")
    public void testExecutionModePrecedenceOfApiModeOverTestProfile() {
        String originalProfile = System.getProperty("test.profile");
        try {
            System.setProperty("base.url", "http://127.0.0.1:8080");
            System.setProperty("api.mode", "offline");
            System.setProperty("test.profile", "integration");
            ConfigManager.reset();
            assertThat(ConfigManager.getInstance().getTestProfile()).isEqualTo("offline");

            System.setProperty("api.mode", "integration");
            System.setProperty("test.profile", "offline");
            ConfigManager.reset();
            assertThat(ConfigManager.getInstance().getTestProfile()).isEqualTo("integration");
        } finally {
            if (originalProfile != null) {
                System.setProperty("test.profile", originalProfile);
            } else {
                System.clearProperty("test.profile");
            }
            ConfigManager.reset();
        }
    }

    @Test(description = "Verify unknown or malformed execution mode values are rejected with IllegalStateException")
    @Story("Mode Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verifies that invalid values for api.mode and test.profile strictly fail fast.")
    public void testExecutionModeRejectsInvalidValues() {
        System.setProperty("api.mode", "invalid_mode");
        ConfigManager.reset();
        assertThatThrownBy(ConfigManager::getInstance)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid execution mode for 'api.mode': 'invalid_mode'");

        String originalProfile = System.getProperty("test.profile");
        try {
            System.clearProperty("api.mode");
            System.setProperty("test.profile", "unsupported_profile");
            ConfigManager.reset();
            assertThatThrownBy(ConfigManager::getInstance)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid execution mode for 'test.profile': 'unsupported_profile'");
        } finally {
            if (originalProfile != null) {
                System.setProperty("test.profile", originalProfile);
            } else {
                System.clearProperty("test.profile");
            }
            ConfigManager.reset();
        }
    }

    @Test(description = "Verify neither property configured preserves existing unconstrained behavior")
    @Story("Mode Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verifies that when neither api.mode nor test.profile is configured, getTestProfile returns null.")
    public void testExecutionModeNeitherConfiguredReturnsNull() {
        String originalProfile = System.getProperty("test.profile");
        try {
            System.clearProperty("api.mode");
            System.clearProperty("test.profile");
            ConfigManager.reset();

            ConfigManager config = ConfigManager.getInstance();
            assertThat(config.getTestProfile()).isNull();
            assertThat(config.isOfflineMode()).isFalse();
            assertThat(config.isIntegrationMode()).isFalse();
        } finally {
            if (originalProfile != null) {
                System.setProperty("test.profile", originalProfile);
            } else {
                System.clearProperty("test.profile");
            }
            ConfigManager.reset();
        }
    }
}
