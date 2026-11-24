package com.sksharma.api.services;

import com.sksharma.api.clients.ApiClient;
import com.sksharma.api.constants.EndpointConstants;
import com.sksharma.api.models.users.User;
import io.restassured.response.Response;

import java.util.Objects;

/**
 * Service encapsulating User domain API interactions.
 */
public class UserService {

    private final ApiClient apiClient;

    public UserService() {
        this(new ApiClient());
    }

    public UserService(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "ApiClient must not be null");
    }

    public Response getAllUsers() {
        return apiClient.get(EndpointConstants.USERS);
    }

    public Response getUserById(int id) {
        return apiClient.get(EndpointConstants.USERS + "/" + id);
    }

    public Response createUser(User user) {
        return apiClient.post(EndpointConstants.USERS_ADD, user);
    }

    public Response updateUser(int id, User user) {
        return apiClient.put(EndpointConstants.USERS + "/" + id, user);
    }

    public Response deleteUser(int id) {
        return apiClient.delete(EndpointConstants.USERS + "/" + id);
    }
}
