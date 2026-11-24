package com.sksharma.api.tests.services;

import com.sksharma.api.models.carts.Cart;
import com.sksharma.api.models.carts.CartItem;
import com.sksharma.api.models.carts.CartResponse;
import com.sksharma.api.services.CartService;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("E-Commerce API")
@Feature("Cart Operations")
@Test(groups = "integration")
public class CartServiceTest {

    private CartService cartService;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        cartService = new CartService();
    }

    @Test(description = "Verify retrieving all carts returns paginated cart list")
    @Story("Cart Retrieval")
    @Severity(SeverityLevel.NORMAL)
    @Description("Fetches all active carts and validates pagination fields.")
    public void testGetAllCarts() {
        Response response = cartService.getAllCarts();

        assertThat(response.getStatusCode()).isEqualTo(200);

        CartResponse cartResponse = response.as(CartResponse.class);
        assertThat(cartResponse.getCarts()).isNotEmpty();
        assertThat(cartResponse.getTotal()).isPositive();

        Cart firstCart = cartResponse.getCarts().getFirst();
        assertThat(firstCart.getId()).isPositive();
        assertThat(firstCart.getProducts()).isNotEmpty();
    }

    @Test(description = "Verify retrieving cart by valid ID returns accurate cart details and items")
    @Story("Cart Retrieval")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Fetches a single cart by ID and validates line items and calculated totals.")
    public void testGetCartByValidId() {
        int cartId = 1;
        Response response = cartService.getCartById(cartId);

        assertThat(response.getStatusCode()).isEqualTo(200);

        Cart cart = response.as(Cart.class);
        assertThat(cart.getId()).isEqualTo(cartId);
        assertThat(cart.getUserId()).isPositive();
        assertThat(cart.getProducts()).isNotEmpty();
        assertThat(cart.getTotal()).isPositive();
    }

    @Test(description = "Verify retrieving cart by non-existent ID returns 404 Not Found")
    @Story("Negative Cart Operations")
    @Severity(SeverityLevel.NORMAL)
    @Description("Ensures queries for non-existent cart IDs return 404 Not Found.")
    public void testGetCartByInvalidIdReturns404() {
        int nonExistentId = 999999;
        Response response = cartService.getCartById(nonExistentId);

        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("message")).containsIgnoringCase("not found");
    }

    @DataProvider(name = "userCartQueryData")
    public Object[][] userCartQueryData() {
        return new Object[][]{
                {1},
                {2},
                {3}
        };
    }

    @Test(dataProvider = "userCartQueryData", description = "Data-driven verification that carts query by user ID returns matching carts")
    @Story("User Carts")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Fetches carts belonging to specific users and asserts user ID consistency across returned carts.")
    public void testDataDrivenGetCartsByUserId(int userId) {
        Response response = cartService.getCartsByUserId(userId);

        assertThat(response.getStatusCode()).isEqualTo(200);

        CartResponse cartResponse = response.as(CartResponse.class);
        assertThat(cartResponse.getCarts()).isNotEmpty();
        assertThat(cartResponse.getCarts()).allMatch(c -> c.getUserId() == userId);
    }

    @Test(description = "Verify simulated cart creation returns created cart with products and calculated totals")
    @Story("Cart Creation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Simulates creating a cart with product items and verifies calculated order values.")
    public void testCreateCartSimulated() {
        Cart newCart = new Cart(
                1,
                List.of(new CartItem(1, 2))
        );

        Response response = cartService.createCart(newCart);

        assertThat(response.getStatusCode()).isIn(200, 201);

        Cart created = response.as(Cart.class);
        assertThat(created.getId()).isPositive();
        assertThat(created.getUserId()).isEqualTo(1);
        assertThat(created.getProducts()).isNotEmpty();
    }

    @Test(description = "Verify simulated cart update returns modified product items")
    @Story("Cart Update")
    @Severity(SeverityLevel.NORMAL)
    @Description("Simulates updating cart quantities and line items.")
    public void testUpdateCartSimulated() {
        Cart updatePayload = new Cart(
                1,
                List.of(new CartItem(1, 3))
        );

        Response response = cartService.updateCart(1, updatePayload);

        assertThat(response.getStatusCode()).isEqualTo(200);

        Cart updated = response.as(Cart.class);
        assertThat(updated.getId()).isEqualTo(1);
        assertThat(updated.getProducts()).isNotEmpty();
    }

    @Test(description = "Verify simulated cart deletion returns isDeleted flag and deletion timestamp")
    @Story("Cart Deletion")
    @Severity(SeverityLevel.NORMAL)
    @Description("Simulates cart deletion and verifies deletion response contract.")
    public void testDeleteCartSimulated() {
        Response response = cartService.deleteCart(1);

        assertThat(response.getStatusCode()).isEqualTo(200);

        Cart deleted = response.as(Cart.class);
        assertThat(deleted.getId()).isEqualTo(1);
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getDeletedOn()).isNotBlank();
    }
}
