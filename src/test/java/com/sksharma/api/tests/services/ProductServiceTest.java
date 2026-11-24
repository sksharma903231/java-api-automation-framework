package com.sksharma.api.tests.services;

import com.sksharma.api.models.products.Product;
import com.sksharma.api.models.products.ProductResponse;
import com.sksharma.api.services.ProductService;
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

import static org.assertj.core.api.Assertions.assertThat;

@Epic("E-Commerce API")
@Feature("Product Catalog")
@Test(groups = "integration")
public class ProductServiceTest {

    private ProductService productService;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        productService = new ProductService();
    }

    @Test(description = "Verify retrieving all products returns a valid catalog list")
    @Story("Product Catalog")
    @Severity(SeverityLevel.NORMAL)
    @Description("Fetches all products and verifies non-empty product catalog with pagination.")
    public void testGetAllProducts() {
        Response response = productService.getAllProducts();

        assertThat(response.getStatusCode()).isEqualTo(200);

        ProductResponse productResponse = response.as(ProductResponse.class);
        assertThat(productResponse.getProducts()).isNotEmpty();
        assertThat(productResponse.getTotal()).isPositive();
        assertThat(productResponse.getLimit()).isPositive();

        Product first = productResponse.getProducts().getFirst();
        assertThat(first.getId()).isPositive();
        assertThat(first.getTitle()).isNotBlank();
        assertThat(first.getPrice()).isPositive();
    }

    @Test(description = "Verify retrieving product by valid ID returns accurate product details")
    @Story("Product Retrieval")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Fetches a single product by ID and verifies accurate field mapping.")
    public void testGetProductByValidId() {
        int productId = 1;
        Response response = productService.getProductById(productId);

        assertThat(response.getStatusCode()).isEqualTo(200);

        Product product = response.as(Product.class);
        assertThat(product.getId()).isEqualTo(productId);
        assertThat(product.getTitle()).isNotBlank();
        assertThat(product.getPrice()).isPositive();
        assertThat(product.getCategory()).isNotBlank();
    }

    @Test(description = "Verify retrieving product by non-existent ID returns 404 Not Found")
    @Story("Negative Product Operations")
    @Severity(SeverityLevel.NORMAL)
    @Description("Ensures queries for non-existent product IDs return 404 Not Found.")
    public void testGetProductByInvalidIdReturns404() {
        int nonExistentId = 999999;
        Response response = productService.getProductById(nonExistentId);

        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("message")).containsIgnoringCase("not found");
    }

    @DataProvider(name = "productSearchData")
    public Object[][] productSearchData() {
        return com.sksharma.api.tests.utils.TestDataLoader.loadAsDataProvider("testdata/product-searches.json");
    }

    @Test(dataProvider = "productSearchData", description = "Data-driven verification that product search returns items matching keywords")
    @Story("Product Search")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Executes catalog search across multiple query keywords and validates relevant items are returned.")
    public void testDataDrivenProductSearch(com.fasterxml.jackson.databind.JsonNode data) {
        String keyword = data.get("query").asText();
        Response response = productService.searchProducts(keyword);

        assertThat(response.getStatusCode()).isEqualTo(200);

        ProductResponse productResponse = response.as(ProductResponse.class);
        assertThat(productResponse.getProducts()).isNotEmpty();

        boolean anyMatch = productResponse.getProducts().stream()
                .anyMatch(p -> p.getTitle().toLowerCase().contains(keyword)
                        || (p.getDescription() != null && p.getDescription().toLowerCase().contains(keyword))
                        || (p.getCategory() != null && p.getCategory().toLowerCase().contains(keyword)));
        assertThat(anyMatch).as("At least one product should match search keyword '%s'", keyword).isTrue();
    }

    @Test(description = "Verify simulated product creation returns created product with assigned ID")
    @Story("Product Creation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Simulates creation of a new product in the catalog and validates generated response payload.")
    public void testCreateProductSimulated() {
        Product newProduct = new Product(
                "Ergonomic Keyboard",
                "High quality wireless mechanical keyboard",
                89.99,
                "electronics",
                45
        );

        Response response = productService.createProduct(newProduct);

        assertThat(response.getStatusCode()).isIn(200, 201);

        Product created = response.as(Product.class);
        assertThat(created.getId()).isPositive();
        assertThat(created.getTitle()).isEqualTo("Ergonomic Keyboard");
        assertThat(created.getPrice()).isEqualTo(89.99);
        assertThat(created.getCategory()).isEqualTo("electronics");
    }

    @Test(description = "Verify simulated product update returns modified fields")
    @Story("Product Update")
    @Severity(SeverityLevel.NORMAL)
    @Description("Simulates updating fields on an existing product.")
    public void testUpdateProductSimulated() {
        Product updatePayload = new Product();
        updatePayload.setTitle("Updated Product Title Via Service");
        updatePayload.setPrice(129.99);

        Response response = productService.updateProduct(1, updatePayload);

        assertThat(response.getStatusCode()).isEqualTo(200);

        Product updated = response.as(Product.class);
        assertThat(updated.getId()).isEqualTo(1);
        assertThat(updated.getTitle()).isEqualTo("Updated Product Title Via Service");
        assertThat(updated.getPrice()).isEqualTo(129.99);
    }

    @Test(description = "Verify simulated product deletion returns isDeleted flag and deletion timestamp")
    @Story("Product Deletion")
    @Severity(SeverityLevel.NORMAL)
    @Description("Simulates deletion of a product and validates deletion metadata.")
    public void testDeleteProductSimulated() {
        Response response = productService.deleteProduct(1);

        assertThat(response.getStatusCode()).isEqualTo(200);

        Product deleted = response.as(Product.class);
        assertThat(deleted.getId()).isEqualTo(1);
        assertThat(deleted.getIsDeleted()).isTrue();
        assertThat(deleted.getDeletedOn()).isNotBlank();
    }
}
