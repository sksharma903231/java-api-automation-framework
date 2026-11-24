package com.sksharma.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Central configuration manager for runtime environment settings.
 * Resolves values using the precedence:
 * 1. Java System Properties (-Dkey=val)
 * 2. OS Environment Variables (KEY_VAL)
 * 3. Classpath config.properties
 *
 * <p>Enforces strict URL security, configurable exact-host allowlisting,
 * loopback destination support for offline testing, and fail-fast property parsing.</p>
 */
public final class ConfigManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigManager.class);
    private static final String DEFAULT_CONFIG_FILE = "config.properties";
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_PORT = 65535;
    public static final String MODE_OFFLINE = "offline";
    public static final String MODE_INTEGRATION = "integration";

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

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalStateException("Property 'base.url' must include a valid host: " + baseUrl);
            }

            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > MAX_PORT)) {
                throw new IllegalStateException("Property 'base.url' has invalid port: " + port);
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

            // Exact host allowlisting check for base.url
            if (!isHostAllowed(host)) {
                throw new IllegalStateException(
                        "Host '" + host + "' in 'base.url' is not permitted by allowlist. Allowed hosts: "
                                + getAllowedHosts());
            }

            // If explicitly configured in offline mode via api.mode, base.url must be loopback
            String explicitMode = getProperty("api.mode");
            if ("offline".equalsIgnoreCase(explicitMode) && !isLoopbackHost(host)) {
                throw new IllegalStateException(
                        "Property 'base.url' must target a loopback destination in offline mode, got: " + baseUrl);
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

        String allowedHostsProp = getProperty("allowed.hosts");
        if (allowedHostsProp != null && allowedHostsProp.trim().isEmpty()) {
            throw new IllegalStateException("Property 'allowed.hosts' must not be empty if specified.");
        }

        isBodyLoggingEnabled();
        getTestProfile();
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
            throw new IllegalStateException(
                    "Invalid integer format for configuration property '" + key + "': '" + value + "'", e);
        }
    }

    public boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException(
                "Invalid boolean format for configuration property '" + key + "': '" + value + "'. Expected 'true' or 'false'.");
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

    /**
     * Returns an unmodifiable set of exact host names permitted for external requests.
     */
    public Set<String> getAllowedHosts() {
        String hostsProp = getProperty("allowed.hosts");
        if (hostsProp == null || hostsProp.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String part : hostsProp.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * Determines whether the given host is an allowed loopback or an exact match in the configured allowlist.
     */
    public boolean isHostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String cleanHost = host.trim().toLowerCase(Locale.ROOT);
        if (isLoopbackHost(cleanHost)) {
            return true;
        }
        return getAllowedHosts().contains(cleanHost);
    }

    /**
     * Evaluates whether a host belongs to the local loopback space:
     * localhost, IPv4 loopback (127.0.0.0/8), or IPv6 loopback (::1, [::1]).
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String clean = host.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if ("localhost".equals(clean) || "::1".equals(clean) || "0:0:0:0:0:0:0:1".equals(clean)) {
            return true;
        }
        if (clean.startsWith("127.")) {
            String[] octets = clean.split("\\.");
            if (octets.length == 4) {
                try {
                    for (String octetStr : octets) {
                        int octet = Integer.parseInt(octetStr);
                        if (octet < 0 || octet > 255) {
                            return false;
                        }
                    }
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Returns the active execution mode or test profile.
     * Evaluates 'api.mode' first, followed by 'test.profile'.
     * Only accepts 'offline' and 'integration' as explicit values.
     *
     * @return the normalized mode ("offline" or "integration"), or null if neither property is configured
     * @throws IllegalStateException if an unknown or malformed mode is configured
     */
    public String getTestProfile() {
        String mode = getProperty("api.mode");
        if (mode != null && !mode.isBlank()) {
            String normalized = mode.trim().toLowerCase(Locale.ROOT);
            if (!MODE_OFFLINE.equals(normalized) && !MODE_INTEGRATION.equals(normalized)) {
                throw new IllegalStateException(
                        "Invalid execution mode for 'api.mode': '" + mode
                                + "'. Permitted values are 'offline' and 'integration'.");
            }
            return normalized;
        }
        String profile = getProperty("test.profile");
        if (profile != null && !profile.isBlank()) {
            String normalized = profile.trim().toLowerCase(Locale.ROOT);
            if (!MODE_OFFLINE.equals(normalized) && !MODE_INTEGRATION.equals(normalized)) {
                throw new IllegalStateException(
                        "Invalid execution mode for 'test.profile': '" + profile
                                + "'. Permitted values are 'offline' and 'integration'.");
            }
            return normalized;
        }
        return null;
    }

    public boolean isOfflineMode() {
        return "offline".equalsIgnoreCase(getTestProfile());
    }

    public boolean isIntegrationMode() {
        return "integration".equalsIgnoreCase(getTestProfile());
    }

    /**
     * Resolves a target endpoint against the base URL.
     * Handles absolute URLs, protocol-relative URLs, and relative endpoint paths.
     */
    public String resolveRequestUrl(String baseUrl, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return baseUrl;
        }
        String trimmedEndpoint = endpoint.trim();
        if (trimmedEndpoint.startsWith("//")) {
            String scheme = (baseUrl != null && baseUrl.startsWith("https://")) ? "https:" : "http:";
            return scheme + trimmedEndpoint;
        }
        if (trimmedEndpoint.startsWith("http://") || trimmedEndpoint.startsWith("https://")
                || (trimmedEndpoint.contains("://") && !trimmedEndpoint.startsWith("/"))) {
            return trimmedEndpoint;
        }
        if (baseUrl == null) {
            return trimmedEndpoint;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedEndpoint = trimmedEndpoint.startsWith("/") ? trimmedEndpoint : "/" + trimmedEndpoint;
        return normalizedBase + normalizedEndpoint;
    }

    /**
     * Validates that a destination URL adheres to security policies:
     * - Uses http or https scheme.
     * - Contains a valid host without user credentials or fragments.
     * - In offline mode: host must be a loopback destination.
     * - In integration / unconstrained mode: host must be in the exact-host allowlist or loopback.
     */
    public void validateRequestUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Request URL must not be null or blank");
        }

        // Replace template placeholders like {id} to allow standard URI parsing
        String parseableUrl = url.replaceAll("\\{[^}]*\\}", "placeholder");

        URI uri;
        try {
            uri = new URI(parseableUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Malformed request URL: " + url, e);
        }

        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Request URL must be an absolute URI: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Request URL must use http or https scheme, got: " + url);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Request URL must contain a valid host: " + url);
        }

        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > MAX_PORT)) {
            throw new IllegalArgumentException("Request URL has invalid port: " + port);
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Request URL must not contain embedded user credentials: " + url);
        }

        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Request URL must not contain URI fragments: " + url);
        }

        boolean loopback = isLoopbackHost(host);

        if (isOfflineMode()) {
            if (!loopback) {
                throw new IllegalStateException(
                        "Offline mode strictly prohibits external network calls to: " + host
                                + ". Only loopback destinations (localhost, 127.0.0.1, ::1) are permitted in offline mode.");
            }
        } else {
            if (!loopback && !isHostAllowed(host)) {
                throw new IllegalStateException(
                        "Host '" + host + "' is not permitted by allowlist. Allowed hosts: " + getAllowedHosts());
            }
        }
    }
}
