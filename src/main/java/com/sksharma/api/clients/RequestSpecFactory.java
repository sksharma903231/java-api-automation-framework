package com.sksharma.api.clients;

import com.sksharma.api.config.ConfigManager;
import com.sksharma.api.constants.HeaderConstants;
import com.sksharma.api.filters.SensitiveDataRedactionFilter;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Factory for creating fresh RequestSpecification instances per execution.
 * Configures base URI, timeouts, content types, and security constraints.
 */
public final class RequestSpecFactory {

    private RequestSpecFactory() {
    }

    /**
     * Builds a fresh RequestSpecification with configured base URI, timeouts, and redaction filter.
     * Retains strict TLS verification and disables blind redirect following to prevent credential leakage.
     */
    public static RequestSpecification getDefaultRequestSpec() {
        ConfigManager config = ConfigManager.getInstance();
        int timeoutMillis = config.getHttpTimeoutSeconds() * 1000;

        RestAssuredConfig restAssuredConfig = RestAssuredConfig.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMillis)
                        .setParam("http.socket.timeout", timeoutMillis)
                        .setParam("http.connection-manager.timeout", (long) timeoutMillis))
                .redirect(RedirectConfig.redirectConfig().followRedirects(false));

        return new RequestSpecBuilder()
                .setBaseUri(config.getBaseUrl())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(restAssuredConfig)
                .addFilter(new SensitiveDataRedactionFilter())
                .build();
    }

    /**
     * Creates an authenticated RequestSpecification enriched with Bearer authorization.
     */
    public static RequestSpecification getAuthenticatedRequestSpec(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Authentication token must not be null or blank");
        }
        return new RequestSpecBuilder()
                .addHeader(HeaderConstants.AUTHORIZATION, HeaderConstants.BEARER_PREFIX + token)
                .build();
    }
}
