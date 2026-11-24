package com.sksharma.api.tests.services;

import com.sksharma.api.models.users.User;
import com.sksharma.api.models.users.UserResponse;
import com.sksharma.api.services.UserService;
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
@Feature("User Management")
@Test(groups = "integration")
public class UserServiceTest {

    private UserService userService;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        userService = new UserService();
    }

    @Test(description = "Verify retrieving all users returns a paginated list of users")
    @Story("User Retrieval")
    @Severity(SeverityLevel.NORMAL)
    @Description("Fetches all users and validates list pagination metadata.")
    public void testGetAllUsers() {
        Response response = userService.getAllUsers();

        assertThat(response.getStatusCode()).isEqualTo(200);

        UserResponse userResponse = response.as(UserResponse.class);
        assertThat(userResponse.getUsers()).isNotEmpty();
        assertThat(userResponse.getTotal()).isPositive();
        assertThat(userResponse.getLimit()).isPositive();

        User firstUser = userResponse.getUsers().getFirst();
        assertThat(firstUser.getId()).isPositive();
        assertThat(firstUser.getEmail()).isNotBlank();
    }

    @Test(description = "Verify retrieving a user by valid ID returns the corresponding user profile")
    @Story("User Retrieval")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Fetches a user profile by valid ID and verifies identity fields.")
    public void testGetUserByValidId() {
        int userId = 1;
        Response response = userService.getUserById(userId);

        assertThat(response.getStatusCode()).isEqualTo(200);

        User user = response.as(User.class);
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getFirstName()).isNotBlank();
        assertThat(user.getEmail()).contains("@");
    }

    @DataProvider(name = "boundaryUserIdsData")
    public Object[][] boundaryUserIdsData() {
        return com.sksharma.api.tests.utils.TestDataLoader.loadAsDataProvider("testdata/user-boundary-ids.json");
    }

    @Test(dataProvider = "boundaryUserIdsData", description = "Data-driven verification that invalid and boundary user IDs return 404 Not Found")
    @Story("Negative User Operations")
    @Severity(SeverityLevel.NORMAL)
    @Description("Validates that queries with zero, negative, or out-of-range user IDs return 404 Not Found.")
    public void testDataDrivenUserBoundaryIds(com.fasterxml.jackson.databind.JsonNode data) {
        int id = data.get("id").asInt();
        int expectedStatus = data.get("expectedStatus").asInt();

        Response response = userService.getUserById(id);

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.jsonPath().getString("message")).containsIgnoringCase("not found");
    }

    @Test(description = "Verify simulated user creation returns created user payload with generated ID")
    @Story("User Creation")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Simulates creation of a new user and asserts response attributes and assigned ID.")
    public void testCreateUserSimulated() {
        User newUser = new User(
                "Alex",
                "Morgan",
                29,
                "alex.morgan@example.com",
                "alex.morgan"
        );

        Response response = userService.createUser(newUser);

        assertThat(response.getStatusCode()).isIn(200, 201);

        User createdUser = response.as(User.class);
        assertThat(createdUser.getId()).isPositive();
        assertThat(createdUser.getFirstName()).isEqualTo("Alex");
        assertThat(createdUser.getLastName()).isEqualTo("Morgan");
        assertThat(createdUser.getEmail()).isEqualTo("alex.morgan@example.com");
    }

    @Test(description = "Verify simulated user update returns modified user attributes")
    @Story("User Update")
    @Severity(SeverityLevel.NORMAL)
    @Description("Simulates modification of user fields and validates echoed updated values.")
    public void testUpdateUserSimulated() {
        User updatePayload = new User();
        updatePayload.setFirstName("Alexander");
        updatePayload.setAge(30);

        Response response = userService.updateUser(1, updatePayload);

        assertThat(response.getStatusCode()).isEqualTo(200);

        User updatedUser = response.as(User.class);
        assertThat(updatedUser.getId()).isEqualTo(1);
        assertThat(updatedUser.getFirstName()).isEqualTo("Alexander");
        assertThat(updatedUser.getAge()).isEqualTo(30);
    }

    @Test(description = "Verify simulated user deletion returns isDeleted flag and deletion timestamp")
    @Story("User Deletion")
    @Severity(SeverityLevel.NORMAL)
    @Description("Simulates user deletion and verifies deletion response contract.")
    public void testDeleteUserSimulated() {
        Response response = userService.deleteUser(1);

        assertThat(response.getStatusCode()).isEqualTo(200);

        User deletedUser = response.as(User.class);
        assertThat(deletedUser.getId()).isEqualTo(1);
        assertThat(deletedUser.getIsDeleted()).isTrue();
        assertThat(deletedUser.getDeletedOn()).isNotBlank();
    }
}
