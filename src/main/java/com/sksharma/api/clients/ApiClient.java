package com.sksharma.api.clients;

import com.sksharma.api.config.ConfigManager;
import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RedirectConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.RequestSpecification;

import java.net.URI;
import java.util.Map;

/**
 * Reusable HTTP client centralizing HTTP method execution across the framework.
 * Does not contain endpoint paths or business operations.
 * Enforces URL security, host allowlisting, redirect restrictions, and mode constraints prior to dispatch.
 */
public class ApiClient {

    public Response get(String endpoint) {
        return get(endpoint, null, null);
    }

    public Response get(String endpoint, Map<String, ?> queryParams) {
        return get(endpoint, queryParams, null);
    }

    public Response get(String endpoint, RequestSpecification customSpec) {
        return get(endpoint, null, customSpec);
    }

    public Response get(String endpoint, Map<String, ?> queryParams, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(endpoint, queryParams, customSpec);
        return req.when().get(endpoint);
    }

    public Response post(String endpoint, Object body) {
        return post(endpoint, body, null);
    }

    public Response post(String endpoint, Object body, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(endpoint, null, customSpec);
        if (body != null) {
            req.body(body);
        }
        return req.when().post(endpoint);
    }

    public Response put(String endpoint, Object body) {
        return put(endpoint, body, null);
    }

    public Response put(String endpoint, Object body, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(endpoint, null, customSpec);
        if (body != null) {
            req.body(body);
        }
        return req.when().put(endpoint);
    }

    public Response patch(String endpoint, Object body) {
        return patch(endpoint, body, null);
    }

    public Response patch(String endpoint, Object body, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(endpoint, null, customSpec);
        if (body != null) {
            req.body(body);
        }
        return req.when().patch(endpoint);
    }

    public Response delete(String endpoint) {
        return delete(endpoint, null);
    }

    public Response delete(String endpoint, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(endpoint, null, customSpec);
        return req.when().delete(endpoint);
    }

    private RequestSpecification prepareRequest(String endpoint, Map<String, ?> queryParams,
                                                RequestSpecification customSpec) {
        ConfigManager config = ConfigManager.getInstance();
        String baseUrl = config.getBaseUrl();
        URI parsedBaseUri = URI.create(baseUrl);
        int basePort = parsedBaseUri.getPort();

        // Guard against protocol-relative authority injection before specification resolution
        if (endpoint != null && endpoint.startsWith("//")) {
            String scheme = baseUrl.startsWith("https://") ? "https:" : "http:";
            config.validateRequestUrl(scheme + endpoint);
        }

        if (customSpec instanceof FilterableRequestSpecification filterable) {
            String bp = filterable.getBasePath();
            if (bp != null && !bp.isBlank()) {
                if (bp.startsWith("//")) {
                    String scheme = baseUrl.startsWith("https://") ? "https:" : "http:";
                    config.validateRequestUrl(scheme + bp + (endpoint != null ? endpoint : ""));
                } else if (bp.contains("://")) {
                    config.validateRequestUrl(bp + (endpoint != null ? endpoint : ""));
                }
            }
        }

        FilterableRequestSpecification spec = (FilterableRequestSpecification) RestAssured.given()
                .spec(RequestSpecFactory.getDefaultRequestSpec());
        if (customSpec != null) {
            spec.spec(customSpec);
        }

        // Lock destination baseUri and basePort to configured values, preventing customSpec overrides
        spec.baseUri(baseUrl);
        spec.port(basePort);

        if (queryParams != null && !queryParams.isEmpty()) {
            spec.queryParams(queryParams);
        }

        if (endpoint != null && !endpoint.isBlank()) {
            spec.path(endpoint);
        }

        // Establish effective destination using REST Assured's exact URI resolution rules
        String effectiveDestination = spec.getURI();
        if (effectiveDestination == null || effectiveDestination.isBlank()) {
            throw new IllegalStateException("Failed to establish effective destination for endpoint: " + endpoint);
        }

        // Validate the exact destination established by REST Assured
        config.validateRequestUrl(effectiveDestination);

        // Enforce destination validation filter at the final execution layer right before dispatch
        spec.filter((requestSpec, responseSpec, ctx) -> {
            String dispatchUri = requestSpec.getURI();
            if (dispatchUri == null || dispatchUri.isBlank()) {
                throw new IllegalStateException("Cannot dispatch request with empty destination URI");
            }
            config.validateRequestUrl(dispatchUri);
            return ctx.next(requestSpec, responseSpec);
        });

        // Enforce redirect restrictions and timeouts at the final execution layer
        int timeoutMillis = config.getHttpTimeoutSeconds() * 1000;
        spec.config(RestAssuredConfig.config()
                .redirect(RedirectConfig.redirectConfig().followRedirects(false))
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", timeoutMillis)
                        .setParam("http.socket.timeout", timeoutMillis)
                        .setParam("http.connection-manager.timeout", (long) timeoutMillis)));

        return spec;
    }
}
