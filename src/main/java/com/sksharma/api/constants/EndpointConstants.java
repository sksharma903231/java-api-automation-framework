package com.sksharma.api.constants;

/**
 * Shared API endpoint paths for the target service (DummyJSON).
 */
public final class EndpointConstants {

    private EndpointConstants() {
        // Prevent instantiation
    }

    // Diagnostics
    public static final String TEST = "/test";

    // Authentication
    public static final String AUTH_LOGIN = "/auth/login";
    public static final String AUTH_ME = "/auth/me";
    public static final String AUTH_REFRESH = "/auth/refresh";

    // Users
    public static final String USERS = "/users";
    public static final String USERS_ID = "/users/{id}";
    public static final String USERS_SEARCH = "/users/search";
    public static final String USERS_FILTER = "/users/filter";
    public static final String USERS_ADD = "/users/add";

    // Products
    public static final String PRODUCTS = "/products";
    public static final String PRODUCTS_ID = "/products/{id}";
    public static final String PRODUCTS_SEARCH = "/products/search";
    public static final String PRODUCTS_CATEGORIES = "/products/categories";
    public static final String PRODUCTS_CATEGORY = "/products/category/{category}";
    public static final String PRODUCTS_ADD = "/products/add";

    // Carts
    public static final String CARTS = "/carts";
    public static final String CARTS_ID = "/carts/{id}";
    public static final String CARTS_USER = "/carts/user/{userId}";
    public static final String CARTS_ADD = "/carts/add";
}
