package com.sksharma.api.services;

import com.sksharma.api.clients.ApiClient;
import com.sksharma.api.clients.RequestSpecFactory;
import com.sksharma.api.constants.EndpointConstants;
import com.sksharma.api.models.auth.LoginRequest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Objects;

/**
 * Service encapsulating authentication operations against the target API.
 */
public class AuthService {

    private final ApiClient apiClient;

    public AuthService() {
        this(new ApiClient());
    }

    public AuthService(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "ApiClient must not be null");
    }

    public Response login(LoginRequest loginRequest) {
        return apiClient.post(EndpointConstants.AUTH_LOGIN, loginRequest);
    }

    public Response login(String username, String password) {
        return login(new LoginRequest(username, password));
    }

    public Response getAuthenticatedUserProfile() {
        return apiClient.get(EndpointConstants.AUTH_ME);
    }

    public Response getAuthenticatedUserProfile(String token) {
        RequestSpecification authSpec = RequestSpecFactory.getAuthenticatedRequestSpec(token);
        return apiClient.get(EndpointConstants.AUTH_ME, authSpec);
    }
}
