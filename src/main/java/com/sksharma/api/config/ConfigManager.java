package com.sksharma.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Central configuration manager for runtime environment settings.
 * Resolves values using the precedence:
 * 1. Java System Properties (-Dkey=val)
 * 2. OS Environment Variables (KEY_VAL)
 * 3. Classpath config.properties
 */
public final class ConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigManager.class);
    private static final String DEFAULT_CONFIG_FILE = "config.properties";
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private static volatile ConfigManager instance;
    private final Properties properties = new Properties();

    private ConfigManager() {
        loadProperties(DEFAULT_CONFIG_FILE);
        validateConfiguration();
    }

    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (ConfigManager.class) {
                if (instance == null) {
                    instance = new ConfigManager();
                }
            }
        }
        return instance;
    }

    public static synchronized void reset() {
        instance = null;
    }

    private void loadProperties(String configFile) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(configFile)) {
            if (input == null) {
                LOG.warn("Configuration file '{}' not found on classpath. Falling back to environment/system properties.", configFile);
                return;
            }
            properties.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load configuration file: " + configFile, e);
        }
    }

    private void validateConfiguration() {
        String baseUrl = getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Required configuration 'base.url' is missing or empty.");
        }

        try {
            URI uri = new URI(baseUrl);
            if (!uri.isAbsolute()) {
                throw new IllegalStateException("Property 'base.url' must be an absolute URI: " + baseUrl);
            }

            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalStateException("Property 'base.url' must use http or https scheme, got: " + baseUrl);
            }

            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException("Property 'base.url' must include a valid host: " + baseUrl);
            }

            if (uri.getUserInfo() != null) {
                throw new IllegalStateException("Property 'base.url' must not contain embedded user credentials: " + baseUrl);
            }

            if (uri.getQuery() != null) {
                throw new IllegalStateException("Property 'base.url' must not contain query parameters: " + baseUrl);
            }

            if (uri.getFragment() != null) {
                throw new IllegalStateException("Property 'base.url' must not contain URI fragments: " + baseUrl);
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Property 'base.url' is not a valid URI syntax: " + baseUrl, e);
        }

        int timeout = getHttpTimeoutSeconds();
        if (timeout < MIN_TIMEOUT_SECONDS || timeout > MAX_TIMEOUT_SECONDS) {
            throw new IllegalStateException(
                    "Property 'http.timeout.seconds' must be between " + MIN_TIMEOUT_SECONDS
                            + " and " + MAX_TIMEOUT_SECONDS + " seconds, got: " + timeout);
        }
    }

    public String getProperty(String key) {
        Objects.requireNonNull(key, "Property key must not be null");

        String systemProp = System.getProperty(key);
        if (systemProp != null && !systemProp.isBlank()) {
            return systemProp.trim();
        }

        String envKey = key.replace('.', '_').toUpperCase(Locale.ROOT);
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }

        String directEnvValue = System.getenv(key);
        if (directEnvValue != null && !directEnvValue.isBlank()) {
            return directEnvValue.trim();
        }

        String fileValue = properties.getProperty(key);
        if (fileValue != null) {
            return fileValue.trim();
        }

        return null;
    }

    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return (value != null) ? value : defaultValue;
    }

    public int getIntProperty(String key, int defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warn("Invalid integer for property '{}': '{}'. Falling back to default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public String getBaseUrl() {
        return getProperty("base.url");
    }

    public int getHttpTimeoutSeconds() {
        return getIntProperty("http.timeout.seconds", 10);
    }

    public boolean isBodyLoggingEnabled() {
        return getBooleanProperty("logging.include.body", false);
    }
}
