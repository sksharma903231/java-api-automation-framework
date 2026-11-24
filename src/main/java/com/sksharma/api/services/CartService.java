package com.sksharma.api.services;

import com.sksharma.api.clients.ApiClient;
import com.sksharma.api.constants.EndpointConstants;
import com.sksharma.api.models.carts.Cart;
import io.restassured.response.Response;

import java.util.Objects;

/**
 * Service encapsulating Cart domain API interactions.
 */
public class CartService {

    private final ApiClient apiClient;

    public CartService() {
        this(new ApiClient());
    }

    public CartService(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "ApiClient must not be null");
    }

    public Response getAllCarts() {
        return apiClient.get(EndpointConstants.CARTS);
    }

    public Response getCartById(int id) {
        return apiClient.get(EndpointConstants.CARTS + "/" + id);
    }

    public Response getCartsByUserId(int userId) {
        return apiClient.get(EndpointConstants.CARTS + "/user/" + userId);
    }

    public Response createCart(Cart cart) {
        return apiClient.post(EndpointConstants.CARTS_ADD, cart);
    }

    public Response updateCart(int id, Cart cart) {
        return apiClient.put(EndpointConstants.CARTS + "/" + id, cart);
    }

    public Response deleteCart(int id) {
        return apiClient.delete(EndpointConstants.CARTS + "/" + id);
    }
}
