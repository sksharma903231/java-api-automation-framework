package com.sksharma.api.clients;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

/**
 * Reusable HTTP client centralizing HTTP method execution across the framework.
 * Does not contain endpoint paths or business operations.
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
        RequestSpecification req = prepareRequest(customSpec);
        if (queryParams != null && !queryParams.isEmpty()) {
            req.queryParams(queryParams);
        }
        return req.when().get(endpoint);
    }

    public Response post(String endpoint, Object body) {
        return post(endpoint, body, null);
    }

    public Response post(String endpoint, Object body, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(customSpec);
        if (body != null) {
            req.body(body);
        }
        return req.when().post(endpoint);
    }

    public Response put(String endpoint, Object body) {
        return put(endpoint, body, null);
    }

    public Response put(String endpoint, Object body, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(customSpec);
        if (body != null) {
            req.body(body);
        }
        return req.when().put(endpoint);
    }

    public Response patch(String endpoint, Object body) {
        return patch(endpoint, body, null);
    }

    public Response patch(String endpoint, Object body, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(customSpec);
        if (body != null) {
            req.body(body);
        }
        return req.when().patch(endpoint);
    }

    public Response delete(String endpoint) {
        return delete(endpoint, null);
    }

    public Response delete(String endpoint, RequestSpecification customSpec) {
        RequestSpecification req = prepareRequest(customSpec);
        return req.when().delete(endpoint);
    }

    private RequestSpecification prepareRequest(RequestSpecification customSpec) {
        RequestSpecification spec = RestAssured.given().spec(RequestSpecFactory.getDefaultRequestSpec());
        if (customSpec != null) {
            spec.spec(customSpec);
            // Protect against RequestSpecBuilder default baseUri (http://localhost:8080) overriding configured URL
            String configuredBaseUrl = com.sksharma.api.config.ConfigManager.getInstance().getBaseUrl();
            spec.baseUri(configuredBaseUrl);
        }
        return spec;
    }
}
