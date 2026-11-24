package com.sksharma.api.services;

import com.sksharma.api.clients.ApiClient;
import com.sksharma.api.constants.EndpointConstants;
import com.sksharma.api.models.products.Product;
import io.restassured.response.Response;

import java.util.Map;
import java.util.Objects;

/**
 * Service encapsulating Product domain API interactions.
 */
public class ProductService {

    private final ApiClient apiClient;

    public ProductService() {
        this(new ApiClient());
    }

    public ProductService(ApiClient apiClient) {
        this.apiClient = Objects.requireNonNull(apiClient, "ApiClient must not be null");
    }

    public Response getAllProducts() {
        return apiClient.get(EndpointConstants.PRODUCTS);
    }

    public Response getProductById(int id) {
        return apiClient.get(EndpointConstants.PRODUCTS + "/" + id);
    }

    public Response searchProducts(String query) {
        return apiClient.get(EndpointConstants.PRODUCTS_SEARCH, Map.of("q", query));
    }

    public Response createProduct(Product product) {
        return apiClient.post(EndpointConstants.PRODUCTS_ADD, product);
    }

    public Response updateProduct(int id, Product product) {
        return apiClient.put(EndpointConstants.PRODUCTS + "/" + id, product);
    }

    public Response deleteProduct(int id) {
        return apiClient.delete(EndpointConstants.PRODUCTS + "/" + id);
    }
}
