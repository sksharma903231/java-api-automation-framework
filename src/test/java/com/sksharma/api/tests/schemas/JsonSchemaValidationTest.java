package com.sksharma.api.tests.schemas;

import com.sksharma.api.models.auth.LoginRequest;
import com.sksharma.api.services.AuthService;
import com.sksharma.api.services.CartService;
import com.sksharma.api.services.ProductService;
import com.sksharma.api.services.UserService;
import com.sksharma.api.tests.utils.TestCredentials;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.restassured.response.Response;
import org.hamcrest.MatcherAssert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Contract Testing")
@Feature("JSON Schema Validation")
public class JsonSchemaValidationTest {

    private AuthService authService;
    private UserService userService;
    private ProductService productService;
    private CartService cartService;

    @BeforeClass
    public void setup() {
        authService = new AuthService();
        userService = new UserService();
        productService = new ProductService();
        cartService = new CartService();
    }

    @Test(description = "Validate authentication response conforms to login-response-schema.json")
    @Story("Auth Contract Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Validates that /auth/login response strictly complies with JSON Schema definitions.")
    public void testLoginResponseSchemaValidation() {
        Response response = authService.login(new LoginRequest(
                TestCredentials.getDemoUsername(),
                TestCredentials.getDemoPassword()
        ));

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/login-response-schema.json"));
    }

    @Test(description = "Validate single user response conforms to user-schema.json")
    @Story("User Contract Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that /users/{id} response strictly complies with user-schema.json.")
    public void testSingleUserSchemaValidation() {
        Response response = userService.getUserById(1);

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/user-schema.json"));
    }

    @Test(description = "Validate user list response conforms to user-list-schema.json")
    @Story("User Contract Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that /users paginated response strictly complies with user-list-schema.json.")
    public void testUserListSchemaValidation() {
        Response response = userService.getAllUsers();

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/user-list-schema.json"));
    }

    @Test(description = "Validate single product response conforms to product-schema.json")
    @Story("Product Contract Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that /products/{id} response strictly complies with product-schema.json.")
    public void testSingleProductSchemaValidation() {
        Response response = productService.getProductById(1);

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/product-schema.json"));
    }

    @Test(description = "Validate product list response conforms to product-list-schema.json")
    @Story("Product Contract Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that /products paginated response strictly complies with product-list-schema.json.")
    public void testProductListSchemaValidation() {
        Response response = productService.getAllProducts();

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/product-list-schema.json"));
    }

    @Test(description = "Validate product search response conforms to product-list-schema.json")
    @Story("Product Contract Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that /products/search response complies with product-list-schema.json.")
    public void testProductSearchSchemaValidation() {
        Response response = productService.searchProducts("phone");

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/product-list-schema.json"));
    }

    @Test(description = "Validate single cart response conforms to cart-schema.json")
    @Story("Cart Contract Validation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Validates that /carts/{id} response strictly complies with cart-schema.json.")
    public void testSingleCartSchemaValidation() {
        Response response = cartService.getCartById(1);

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/cart-schema.json"));
    }

    @Test(description = "Validate cart list response conforms to cart-list-schema.json")
    @Story("Cart Contract Validation")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that /carts paginated response strictly complies with cart-list-schema.json.")
    public void testCartListSchemaValidation() {
        Response response = cartService.getAllCarts();

        assertThat(response.getStatusCode()).isEqualTo(200);
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/cart-list-schema.json"));
    }

    @Test(description = "Verify JSON schema validator detects mismatches when given a deliberately invalid payload")
    @Story("Negative Contract Validation")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Asserts that JsonSchemaValidator detects type mismatches and missing required attributes.")
    public void testNegativeSchemaValidationWithDeliberatelyInvalidPayload() {
        String invalidPayload = """
                {
                  "id": true,
                  "username": "testuser",
                  "missingTokens": true
                }
                """;

        assertThatThrownBy(() ->
                MatcherAssert.assertThat(
                        invalidPayload,
                        matchesJsonSchemaInClasspath("schemas/login-response-schema.json")
                )
        ).isInstanceOf(AssertionError.class)
                .hasMessageContaining("error: object has missing required properties");
    }
}
